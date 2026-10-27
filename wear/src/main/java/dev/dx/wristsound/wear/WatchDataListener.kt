package dev.dx.wristsound.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class WatchDataListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        holdWristTransitionCpu(this, 60_000).use { runBlocking {
            if (runCatching { watchApp.transport.trustedPeer(event.sourceNodeId) }.getOrNull() == null) return@runBlocking
            watchApp.recoverIfEnabled()
            watchApp.repository.receive(event.sourceNodeId, event.path, event.data)
        } }
    }
    override fun onCapabilityChanged(info: CapabilityInfo) {
        holdWristTransitionCpu(this, 60_000).use { runBlocking { watchApp.recoverIfEnabled(); watchApp.repository.publish() } }
    }
}

class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        context.watchApp.scope.launch {
            try {
                val settings = context.watchApp.store.settings.first()
                if (settings.enabled && (settings.startOnBoot || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)) context.watchApp.startMonitoring()
            } finally { pending.finish() }
        }
    }
}
