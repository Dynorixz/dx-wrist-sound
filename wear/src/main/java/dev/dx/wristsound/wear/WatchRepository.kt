package dev.dx.wristsound.wear

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import dev.dx.wristsound.shared.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class WatchRepository(
    context: Context,
    val store: PersistentStore,
    private val transport: LocalWearTransport,
    val detector: WristDetectionProvider,
) {
    val running = MutableStateFlow(false)
    val wrist = MutableStateFlow(WristState.UNKNOWN)
    val phoneNearby = MutableStateFlow<Boolean?>(null)
    val soundProblem = MutableStateFlow<WatchSoundProblem?>(null)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val sound = WatchSoundController(object : WatchSoundDevice {
        override val ringerMode get() = audio.ringerMode
        override val ringVolume get() = audio.getStreamVolume(AudioManager.STREAM_RING)
        override val hasPolicyAccess get() = notifications.isNotificationPolicyAccessGranted
        override val isVolumeFixed get() = audio.isVolumeFixed
        override fun changeMode(mode: Int) { audio.ringerMode = mode }
        override fun changeVolume(volume: Int) { audio.setStreamVolume(AudioManager.STREAM_RING, volume, 0) }
    }, object : WatchSoundStore {
        override suspend fun saved() = if (store.ownsSound.first()) store.savedSound.first() else null
        override suspend fun save(snapshot: SoundSnapshot?) { store.saveSound(snapshot, snapshot != null) }
    })
    private val mutex = Mutex()
    private val session = UUID.randomUUID().toString()
    private var sequence = 0L
    private var state = WristState.UNKNOWN
    private var detectedAt = 0L
    private var info = DeviceInfo(peerName = "Телефон не найден")
    private var serviceOwner: String? = null

    suspend fun begin(owner: String) = mutex.withLock {
        serviceOwner = owner
        running.value = true; state = WristState.UNKNOWN; detectedAt = 0
        wrist.value = state; phoneNearby.value = null
        info = DeviceInfo(running = true, peerName = "Телефон не найден")
        store.log("Wrist detector started: ${detector.descriptor.value.name}")
        releaseLegacySound()
        persist()
    }
    suspend fun confirmed(value: WristState) {
        mutex.withLock {
            state = value; detectedAt = System.currentTimeMillis(); info = info.copy(error = "")
            wrist.value = value
            store.log("${value.name} confirmed")
            persist()
        }
        publish(WearProtocol.WRIST_PATH)
    }

    suspend fun publish(path: String = WearProtocol.HEARTBEAT_PATH) = mutex.withLock {
        val sensor = detector.descriptor.value
        val snapshot = WatchSnapshot(session, ++sequence, if (running.value) state else WristState.UNKNOWN,
            sensor.name, sensor.available, Build.MODEL, detectedAt, running.value)
        runCatching { transport.nearbyPeers() }.onSuccess { nodes ->
            val settings = store.settings.first()
            if (settings.selectedNode.isEmpty() && nodes.isNotEmpty()) store.updateSettings { it.copy(selectedNode = nodes.first().id) }
            val phoneId = if (settings.selectedNode.isNotEmpty()) settings.selectedNode else nodes.firstOrNull()?.id
            val selected = nodes.filter { it.id == phoneId }
            val connected = selected.isNotEmpty() || transport.nearbyNodes().any { it.id == phoneId }
            phoneNearby.value = connected
            info = info.copy(connected = connected, peerName = selected.firstOrNull()?.displayName ?: info.peerName,
                phoneLost = if (connected) false else info.phoneLost)
            selected.forEach { node ->
                sendPendingAlertSettings(node.id)
                runCatching { transport.send(node.id, path, WearProtocol.encode(snapshot)) }
                    .onSuccess {
                        info = info.copy(lastUpdate = System.currentTimeMillis(), error = if (info.error.startsWith("Data Layer:")) "" else info.error,
                            lastHeartbeat = if (path == WearProtocol.HEARTBEAT_PATH) System.currentTimeMillis() else info.lastHeartbeat)
                    }.onFailure { info = info.copy(error = "Data Layer: ${it.message}"); store.log(info.error) }
            }
        }.onFailure {
            if (!currentCoroutineContext().isActive) throw it
            info = info.copy(error = "Data Layer: ${it.message}"); store.log(info.error)
        }
        persist()
    }
    suspend fun receive(nodeId: String, path: String, bytes: ByteArray) {
        if (runCatching { transport.trustedPeer(nodeId) }.getOrNull() == null) return
        if (path == WearProtocol.REQUEST_PATH || path == WearProtocol.CONFIG_PATH) {
            store.updateSettings { it.copy(selectedNode = nodeId) }
        }
        when (path) {
            WearProtocol.ALERT_CONFIG_ACK_PATH -> {
                val accepted = WearProtocol.decodeConfig(bytes) ?: return
                mutex.withLock {
                    store.updateSettings { local ->
                        if ((local.selectedNode.isEmpty() || local.selectedNode == nodeId) &&
                            local.phoneLossAlert == accepted.phoneLossAlert && local.phoneLossVoice == accepted.phoneLossVoice &&
                            local.phoneLossSeconds == accepted.phoneLossSeconds) local.copy(alertSettingsPending = false) else local
                    }
                }
            }
            WearProtocol.REQUEST_PATH -> publish(WearProtocol.WRIST_PATH)
            WearProtocol.CONFIG_PATH -> {
                val config = WearProtocol.decodeConfig(bytes) ?: return store.log("Invalid phone configuration rejected")
                // Old phones may still send vibration=true. Never change watch sound from it.
                store.updateSettings { it.copy(debounceMillis = config.debounceMillis, startOnBoot = config.startOnBoot, watchVibration = false,
                    offDebounceMillis = config.offDebounceMillis,
                    phoneLossAlert = if (it.alertSettingsPending) it.phoneLossAlert else config.phoneLossAlert,
                    phoneLossVoice = if (it.alertSettingsPending) it.phoneLossVoice else config.phoneLossVoice,
                    phoneLossSeconds = if (it.alertSettingsPending) it.phoneLossSeconds else config.phoneLossSeconds) }
                sendPendingAlertSettings(nodeId)
                mutex.withLock { persist() }
            }
        }
    }
    suspend fun changeAlertSettings(change: (AppSettings) -> AppSettings) = mutex.withLock {
        // Commit the choice and its retry flag in one DataStore transaction.
        store.updateSettings { change(it).copy(alertSettingsPending = true) }
        val node = store.settings.first().selectedNode
        if (node.isNotEmpty()) sendPendingAlertSettings(node)
    }
    private suspend fun sendPendingAlertSettings(node: String) {
        val settings = store.settings.first()
        if (!settings.alertSettingsPending) return
        runCatching { transport.send(node, WearProtocol.ALERT_CONFIG_PATH, WearProtocol.encode(WatchConfig(
            settings.debounceMillis, settings.startOnBoot, false, settings.offDebounceMillis,
            settings.phoneLossAlert, settings.phoneLossVoice, settings.phoneLossSeconds))) }
            .onFailure {
                if (!currentCoroutineContext().isActive) throw it
                store.log("Настройки оповещения сохранены на часах; отправим при подключении")
            }
    }
    private suspend fun releaseLegacySound() {
        noteSoundResult(sound.releaseLegacyVibration())
    }
    private suspend fun noteSoundResult(failure: WatchSoundFailure?) {
        soundProblem.value = failure?.problem
        if (failure != null) store.log("Звук часов: ${failure.problem.message} ${failure.diagnostic}")
    }
    suspend fun refreshSound() = mutex.withLock {
        releaseLegacySound()
        persist()
    }
    private suspend fun persist() {
        val sensor = detector.descriptor.value
        info = info.copy(running = running.value, wrist = state, sensor = sensor.name, sensorAvailable = sensor.available,
            phase = when (state) {
                WristState.ON_WRIST -> AutomationPhase.WATCH_ON_WRIST
                WristState.OFF_WRIST -> AutomationPhase.WATCH_OFF_WRIST
                WristState.UNKNOWN -> AutomationPhase.UNKNOWN
            })
        store.writeInfo(info)
    }
    suspend fun markPhoneLost(value: Boolean) = mutex.withLock {
        info = info.copy(phoneLost = value)
        persist()
    }
    suspend fun notePhoneConnection(value: Boolean) = mutex.withLock {
        phoneNearby.value = value
        info = info.copy(connected = value, phoneLost = if (value) false else info.phoneLost)
        persist()
    }
    suspend fun stop(owner: String? = null) {
        mutex.withLock {
            if (owner != null && owner != serviceOwner) return
            running.value = false; state = WristState.UNKNOWN
            wrist.value = state; phoneNearby.value = null
            info = info.copy(phoneLost = false)
            serviceOwner = null
            releaseLegacySound(); persist(); store.log("Wrist detector stopped")
        }
        publish(WearProtocol.WRIST_PATH)
    }
}
