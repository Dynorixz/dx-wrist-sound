package dev.dx.wristsound.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Inexact, low-frequency checks survive process death. No exact-alarm permission required. */
object MonitorRecovery {
    const val CHECK_ACTION = "dev.dx.wristsound.CHECK_MONITOR"
    private const val INTERVAL = 15 * 60_000L
    private fun pending(context: Context) = PendingIntent.getBroadcast(context, 31,
        Intent(context, MonitorRecoveryReceiver::class.java).setAction(CHECK_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL, pending(context))
    }
    fun cancel(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
}

class MonitorRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MonitorRecovery.CHECK_ACTION) return
        // Even a slow/corrupt settings read must not consume the only scheduled recovery.
        MonitorRecovery.schedule(context)
        val pending = goAsync()
        val cpu = holdWristTransitionCpu(context, 15_000)
        context.watchApp.scope.launch {
            try { withTimeout(12_000) {
                if (context.watchApp.store.settings.first().enabled) {
                    context.watchApp.startMonitoring(recheckSensor = true)
                } else MonitorRecovery.cancel(context)
            } } finally { cpu.close(); pending.finish() }
        }
    }
}
