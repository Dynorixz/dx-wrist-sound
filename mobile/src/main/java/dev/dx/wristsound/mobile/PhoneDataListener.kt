package dev.dx.wristsound.mobile

import com.google.android.gms.wearable.*
import kotlinx.coroutines.runBlocking

/** Bound by Google Play services even when the Activity is closed. */
class PhoneDataListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        runBlocking { phoneApp.repository.receive(event.sourceNodeId, event.path, event.data) }
    }
    override fun onCapabilityChanged(info: CapabilityInfo) {
        runBlocking { phoneApp.repository.refreshConnection() }
    }
}
