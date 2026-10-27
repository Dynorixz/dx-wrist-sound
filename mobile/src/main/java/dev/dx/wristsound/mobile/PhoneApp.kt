package dev.dx.wristsound.mobile

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.dx.wristsound.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class PhoneApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        android.util.Log.e("DXWristSound", "Background error", error)
    })
    val store by lazy { PersistentStore(this, scope) }
    val transport by lazy { LocalWearTransport(this, WearProtocol.WATCH_CAPABILITY) }
    val sound by lazy { SoundModeController(this) }
    val repository by lazy { WristStateRepository(this, store, transport, sound, scope) }

    fun startMonitoring() {
        runCatching {
            check(Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                "Предоставьте разрешение «Устройства поблизости»"
            }
            ContextCompat.startForegroundService(this, Intent(this, PhoneMonitorService::class.java))
        }
            .onFailure { error -> scope.launch {
                store.log("Не удалось запустить мониторинг: ${error.message}")
                store.writeInfo(store.info.first().copy(running = false, error = "Откройте приложение и включите автоматизацию: ${error.message}"))
            } }
    }
}
val Context.phoneApp: PhoneApp get() = applicationContext as PhoneApp
