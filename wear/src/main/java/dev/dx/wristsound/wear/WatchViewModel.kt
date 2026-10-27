package dev.dx.wristsound.wear

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dx.wristsound.shared.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class WatchUiState(
    val settings: AppSettings = AppSettings(), val info: DeviceInfo = DeviceInfo(peerName = "Телефон не найден"),
    val sensor: SensorDescriptor = SensorDescriptor("Проверка датчиков", false, false),
    val voiceStatus: String = "",
    val linkStatus: String = "",
    val soundProblem: WatchSoundProblem? = null,
)
class WatchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchApp
    private val previewPopup = PhoneLossPopup(app, app.store, app.scope)
    private val base = combine(app.store.settings.distinctUntilChanged(), app.store.info.distinctUntilChanged(), app.detector.descriptor, app.repository.running) { settings, info, descriptor, running ->
        WatchUiState(settings, info.copy(running = running), descriptor)
    }
    private val linkStatus = MutableStateFlow("")
    private val soundStatus = combine(app.speech.status, app.chime.status) { voice, chime -> voice to chime }
    val events = app.store.events.map { it.takeLast(15).asReversed() }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.IO)
    val ui = combine(base, soundStatus, linkStatus, app.repository.soundProblem) { state, sound, link, problem -> state.copy(
        voiceStatus = if (state.settings.phoneLossVoice) sound.first else sound.second, linkStatus = link, soundProblem = problem) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WatchUiState(sensor = app.detector.descriptor.value))
    fun setEnabled(value: Boolean) = viewModelScope.launch {
        app.store.updateSettings { it.copy(enabled = value) }
        if (value) app.startMonitoring()
        else {
            MonitorRecovery.cancel(app)
            app.repository.stop()
            app.stopService(Intent(app, WristMonitorService::class.java))
        }
    }
    fun refresh() = viewModelScope.launch {
        if (app.store.settings.first().enabled && !app.repository.running.value) app.startMonitoring()
        else app.repository.refreshSound()
        app.repository.publish()
    }
    fun permissionDenied() = viewModelScope.launch { app.store.log("Доступ к off-body датчику не предоставлен") }
    fun testVoice() { app.speech.speak("Вы забыли телефон") }
    fun testAlert() {
        viewModelScope.launch {
            if (app.store.settings.first().phoneLossVoice) { app.chime.cancel(); app.speech.speak("Вы забыли телефон") }
            else { app.speech.cancel(); app.chime.play() }
        }
    }
    fun changeAlerts(change: (AppSettings) -> AppSettings) = viewModelScope.launch { app.repository.changeAlertSettings(change) }
    fun testPopup() { previewPopup.show {} }
    override fun onCleared() { previewPopup.hide(); super.onCleared() }
    fun dismissLoss() {
        if (app.repository.running.value) app.startService(Intent(app, WristMonitorService::class.java).setAction(PhoneLossMonitor.DISMISS_ACTION))
    }
    fun openOnPhone(link: AppLink) = viewModelScope.launch {
        runCatching {
            val peers = app.transport.nearbyPeers()
            val selected = app.store.settings.first().selectedNode
            val node = peers.firstOrNull { it.id == selected } ?: peers.firstOrNull()
            check(node != null) { "Нет локальной связи с телефоном" }
            app.transport.send(node.id, WearProtocol.AUTHOR_LINK_PATH, link.url.toByteArray(Charsets.UTF_8))
        }.onSuccess { linkStatus.value = "Нажмите уведомление на телефоне. На нём должен быть разрешён показ уведомлений."; app.store.log("Ссылка ${link.title}: нажмите уведомление на телефоне") }
            .onFailure { linkStatus.value = "Не удалось отправить ссылку: ${it.message}"; app.store.log(linkStatus.value) }
    }
}
