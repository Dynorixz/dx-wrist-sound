package dev.dx.wristsound.mobile

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dx.wristsound.shared.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class WatchChoice(val id: String, val name: String)
data class PhoneUiState(
    val settings: AppSettings = AppSettings(),
    val info: DeviceInfo = DeviceInfo(),
    val events: List<EventEntry> = emptyList(),
    val sound: SoundSnapshot = SoundSnapshot(0, 2),
    val policyAccess: Boolean = false,
    val peers: List<WatchChoice> = emptyList(),
    val dndActive: Boolean = false,
)

class PhoneViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PhoneApp
    private val volume = MutableStateFlow(app.sound.current())
    private val policy = MutableStateFlow(app.sound.policyAccess to app.sound.isDndActive)
    val maximum = app.sound.maximum
    private val base = combine(app.store.settings, app.store.info, app.store.events, volume, policy) { settings, info, events, sound, access ->
        PhoneUiState(settings, info, events, sound, access.first, dndActive = access.second)
    }
    val ui = combine(base, app.repository.running, app.repository.peers) { state, running, nodes ->
        state.copy(info = state.info.copy(running = running), peers = nodes.map { WatchChoice(it.id, it.displayName) })
    }.onEach { volume.value = app.sound.current() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PhoneUiState())

    fun refresh() {
        refreshVolume()
        viewModelScope.launch {
            val settings = app.store.settings.first()
            if (settings.enabled && !app.repository.running.value) app.startMonitoring()
            app.repository.refreshConnection(forceRequest = true)
        }
    }
    fun refreshVolume() { volume.value = app.sound.current(); policy.value = app.sound.policyAccess to app.sound.isDndActive }
    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        app.store.updateSettings { it.copy(enabled = enabled) }
        if (enabled) app.startMonitoring()
        else {
            app.repository.stop(restore = true)
            app.stopService(Intent(app, PhoneMonitorService::class.java))
        }
    }
    fun changeSettings(change: (AppSettings) -> AppSettings) = viewModelScope.launch { app.store.updateSettings(change) }
    fun clearLog() = viewModelScope.launch { app.store.clearLog() }
    fun recordPermissionError() = viewModelScope.launch { app.store.log("Для фонового контроля часов нужен доступ к устройствам поблизости") }
}
