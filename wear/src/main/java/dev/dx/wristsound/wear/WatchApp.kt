package dev.dx.wristsound.wear

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.dx.wristsound.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class WatchApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> android.util.Log.e("DXWristSound", "Watch error", error) })
    val store by lazy { PersistentStore(this, scope) }
    val transport by lazy { LocalWearTransport(this, WearProtocol.PHONE_CAPABILITY) }
    val detector by lazy { WristProviderFactory.create(this) }
    val repository by lazy { WatchRepository(this, store, transport, detector) }
    val speech by lazy { OfflineSpeech(this, store, scope) }
    val chime by lazy { LossChime(this, store, scope) }
    fun startMonitoring(recheckSensor: Boolean = false) {
        MonitorRecovery.schedule(this)
        val intent = Intent(this, WristMonitorService::class.java)
        if (recheckSensor) intent.setAction(WristMonitorService.RECHECK_ACTION)
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { error -> scope.launch {
                store.log("Watch FGS start failed: ${error.message}")
                if (!repository.running.value) store.writeInfo(store.info.first().copy(running = false,
                    error = "Разрешите работу в фоне: Разрешения → Работа ночью. ${error.message}"))
            } }
    }
    suspend fun recoverIfEnabled() {
        if (store.settings.first().enabled && !repository.running.value) startMonitoring()
    }
}
val Context.watchApp: WatchApp get() = applicationContext as WatchApp
