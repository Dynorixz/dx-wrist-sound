package dev.dx.wristsound.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.SystemClock
import dev.dx.wristsound.shared.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PhoneBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        context.phoneApp.scope.launch {
            try {
                val settings = context.phoneApp.store.settings.first()
                if (settings.enabled && settings.startOnBoot) context.phoneApp.startMonitoring()
            } finally { pending.finish() }
        }
    }
}

class FallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.phoneApp.scope.launch {
            try {
                val app = context.phoneApp
                if (app.repository.running.value) {
                    app.repository.checkDeadline()
                } else {
                    val settings = app.store.settings.first()
                    val info = app.store.info.first()
                    if (settings.enabled && settings.fallbackEnabled && info.fallbackAt > 0 && System.currentTimeMillis() >= info.fallbackAt) {
                        val staleAt = info.lastKnownUpdate + settings.heartbeatTimeoutSeconds * 1000L
                        if (info.connected && info.lastKnownUpdate > 0 && info.alarmProbeKnown != info.lastKnownUpdate && System.currentTimeMillis() >= staleAt) {
                            val nearby = runCatching { app.transport.nearbyNodes() }
                            if (nearby.isFailure || nearby.getOrThrow().any { it.id == settings.selectedNode }) {
                                val nextAt = System.currentTimeMillis() + AutomationMachine.HEARTBEAT_PROBE_MILLIS
                                app.store.writeInfo(info.copy(alarmProbeKnown = info.lastKnownUpdate, fallbackAt = nextAt))
                                val alarm = context.getSystemService(AlarmManager::class.java)
                                val intent = PendingIntent.getBroadcast(context, 7, Intent(context, FallbackReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + AutomationMachine.HEARTBEAT_PROBE_MILLIS, intent)
                                runCatching { app.transport.send(settings.selectedNode, WearProtocol.REQUEST_PATH, byteArrayOf()) }
                                    .onFailure { app.store.log("Alarm heartbeat probe request failed: ${it.message}") }
                                app.store.log("Alarm heartbeat delayed: request fresh state, wait 30 seconds")
                                return@launch
                            }
                        }
                        if (info.phase == AutomationPhase.DISCONNECTED && info.lastKnownUpdate > 0 && System.currentTimeMillis() < staleAt) {
                            val nearby = runCatching { app.transport.nearbyNodes() }
                            val present = nearby.getOrNull()?.any { it.id == settings.selectedNode } == true
                            if (nearby.isFailure || present) {
                                val nextAt = if (present) staleAt else minOf(staleAt, System.currentTimeMillis() + 30_000)
                                app.store.writeInfo(info.copy(connected = present, wrist = WristState.UNKNOWN,
                                    phase = if (present) AutomationPhase.UNKNOWN else info.phase, fallbackAt = nextAt))
                                app.store.log("Alarm fallback deferred: disconnect not confirmed")
                                val alarm = context.getSystemService(AlarmManager::class.java)
                                val intent = PendingIntent.getBroadcast(context, 7, Intent(context, FallbackReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                    SystemClock.elapsedRealtime() + (nextAt - System.currentTimeMillis()).coerceAtLeast(0), intent)
                                return@launch
                            }
                        }
                        app.sound.apply(SoundSnapshot(app.sound.maximum, 2)).onSuccess {
                            app.store.log("Alarm fallback: phone ring volume -> MAX")
                            app.store.writeInfo(info.copy(phase = AutomationPhase.FALLBACK_SOUND_ON, running = false, error = ""))
                        }.onFailure { app.store.log("Alarm fallback failed: ${it.message}") }
                    }
                }
            } finally { pending.finish() }
        }
    }
}
