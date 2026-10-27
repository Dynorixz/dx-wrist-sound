package dev.dx.wristsound.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.wearable.Node
import dev.dx.wristsound.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WristStateRepository(
    private val context: Context,
    val store: PersistentStore,
    private val transport: LocalWearTransport,
    private val sound: SoundModeController,
    private val scope: CoroutineScope,
) {
    val running = MutableStateFlow(false)
    val peers = MutableStateFlow<List<Node>>(emptyList())
    private val mutex = Mutex()
    private var machine = MachineState(startedAt = SystemClock.elapsedRealtime())
    private var settings = AppSettings()
    private var info = DeviceInfo()
    private var gate = SequenceGate()
    private var lastApplied: Pair<WristState, AppSettings>? = null
    private var fallbackApplied = false
    private var deadlineJob: Job? = null
    private var connectionRetryJob: Job? = null
    private var serviceOwner: String? = null
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val fallbackIntent = PendingIntent.getBroadcast(context, 7, Intent(context, FallbackReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    suspend fun begin(owner: String) {
        mutex.withLock {
            settings = store.settings.first()
            serviceOwner = owner
            info = store.info.first().copy(running = true, connected = false, wrist = WristState.UNKNOWN, error = "")
            machine = MachineState(phase = AutomationPhase.DISCONNECTED, startedAt = SystemClock.elapsedRealtime(), disconnectedAt = SystemClock.elapsedRealtime())
            lastApplied = null; fallbackApplied = false; gate = SequenceGate()
            running.value = true
            store.log("Phone monitor started")
            persist(); scheduleDeadline()
        }
        refreshConnection()
    }

    suspend fun settingsChanged(value: AppSettings) {
        var refresh = false
        mutex.withLock {
            if (value == settings) return
            refresh = value.selectedNode != settings.selectedNode
            settings = value
            lastApplied = null; fallbackApplied = false
            if (refresh) {
                gate = SequenceGate()
                machine = MachineState(startedAt = SystemClock.elapsedRealtime(), disconnectedAt = SystemClock.elapsedRealtime())
                info = info.copy(wrist = WristState.UNKNOWN, connected = false, sensorAvailable = false, sensor = "Не получен")
            }
            if (value.enabled && machine.connected) applyState(machine.wrist)
            scheduleDeadline(); persist()
        }
        sendConfig()
        if (refresh) refreshConnection()
    }

    suspend fun refreshConnection(forceRequest: Boolean = false) {
        runCatching { transport.nearbyPeers() to transport.nearbyNodes() }.onSuccess { (nodes, physicalNodes) ->
            connectionRetryJob?.takeIf { it != currentCoroutineContext()[Job] }?.cancel()
            connectionRetryJob = null
            peers.value = nodes
            var request: String? = null
            mutex.withLock {
                val node = nodes.firstOrNull { it.id == settings.selectedNode }
                    ?: if (settings.selectedNode.isEmpty()) nodes.firstOrNull() else null
                if (node != null && settings.selectedNode.isEmpty()) {
                    store.updateSettings { it.copy(selectedNode = node.id) }
                    settings = settings.copy(selectedNode = node.id)
                }
                val localNode = node ?: physicalNodes.firstOrNull { settings.selectedNode.isNotEmpty() && it.id == settings.selectedNode }
                val wasConnected = machine.connected
                machine = AutomationMachine.connection(machine, localNode != null, SystemClock.elapsedRealtime())
                info = info.copy(connected = localNode != null, peerName = localNode?.displayName ?: info.peerName,
                    error = if (info.error.startsWith("Проверка связи:")) "" else info.error)
                if (wasConnected != machine.connected) {
                    store.log(if (machine.connected) "Watch connected: ${node?.displayName}" else "Watch disconnected")
                    if (node != null) { lastApplied = null; fallbackApplied = false }
                }
                if (node != null && (forceRequest || !wasConnected || machine.wrist == WristState.UNKNOWN)) request = node.id
                scheduleDeadline(); persist()
            }
            request?.let { id ->
                runCatching { transport.send(id, WearProtocol.REQUEST_PATH, byteArrayOf()) }.onFailure { store.log("Snapshot request failed: ${it.message}") }
                sendConfig()
            }
        }.onFailure {
            if (!currentCoroutineContext().isActive) throw it
            mutex.withLock {
                val error = "Проверка связи: ${it.message}"
                if (info.error != error) store.log(error)
                info = info.copy(error = error)
                persist()
            }
            // A failed API request does not prove the watch disconnected.
            if (running.value && settings.enabled) {
                connectionRetryJob?.cancel()
                connectionRetryJob = scope.launch { delay(5_000); refreshConnection() }
            }
        }
    }

    suspend fun disconnected(reason: String) = mutex.withLock {
        if (!settings.enabled) settings = store.settings.first()
        machine = AutomationMachine.connection(machine, false, SystemClock.elapsedRealtime())
        info = info.copy(connected = false)
        store.log(reason)
        scheduleDeadline(); persist()
    }

    suspend fun receive(nodeId: String, path: String, bytes: ByteArray) {
        if (path == WearProtocol.ALERT_CONFIG_PATH) {
            val config = WearProtocol.decodeConfig(bytes) ?: return
            if (runCatching { transport.trustedPeer(nodeId) }.getOrNull() == null) return
            val selected = store.settings.first().selectedNode
            if (selected.isNotEmpty() && selected != nodeId) return
            store.updateSettings { it.copy(phoneLossAlert = config.phoneLossAlert,
                phoneLossVoice = config.phoneLossVoice, phoneLossSeconds = config.phoneLossSeconds) }
            store.log("Настройки оповещения обновлены с часов")
            runCatching { transport.send(nodeId, WearProtocol.ALERT_CONFIG_ACK_PATH, WearProtocol.encode(config)) }
            return
        }
        if (path == WearProtocol.AUTHOR_LINK_PATH) {
            if (bytes.size > 512 || runCatching { transport.trustedPeer(nodeId) }.getOrNull() == null) return
            val selected = store.settings.first().selectedNode
            if (selected.isNotEmpty() && selected != nodeId) return
            val url = bytes.toString(Charsets.UTF_8)
            val link = AppLinks.author(context).firstOrNull { it.url == url } ?: return
            val shown = AuthorLinkNotification.show(context, link)
            store.log(if (shown) "Author link requested on watch: ${link.title}" else "Разрешите уведомления, чтобы открыть ссылку с часов")
            return
        }
        if (path != WearProtocol.WRIST_PATH && path != WearProtocol.HEARTBEAT_PATH) return
        val snapshot = WearProtocol.decodeSnapshot(bytes) ?: return store.log("Rejected malformed watch message")
        val node = runCatching { transport.trustedPeer(nodeId) }.getOrNull() ?: return
        mutex.withLock {
            val persisted = store.settings.first()
            if (!persisted.enabled || (persisted.selectedNode.isNotEmpty() && persisted.selectedNode != nodeId)) return
            settings = persisted
            if (settings.selectedNode.isEmpty()) {
                store.updateSettings { it.copy(selectedNode = nodeId) }
                settings = settings.copy(selectedNode = nodeId)
            }
            if (!gate.accept(snapshot)) return
            val oldState = machine.wrist
            val trustedSnapshot = if (snapshot.monitoring && snapshot.sensorAvailable) snapshot else snapshot.copy(state = WristState.UNKNOWN)
            machine = AutomationMachine.snapshot(machine, trustedSnapshot, SystemClock.elapsedRealtime())
            if (!snapshot.monitoring) machine = machine.copy(disconnectedAt = machine.disconnectedAt ?: SystemClock.elapsedRealtime())
            val now = System.currentTimeMillis()
            info = info.copy(
                connected = true, peerName = snapshot.watchName.ifBlank { node.displayName }, wrist = trustedSnapshot.state,
                sensor = snapshot.sensorName, sensorAvailable = snapshot.sensorAvailable,
                lastUpdate = now,
                lastKnownUpdate = if (trustedSnapshot.state != WristState.UNKNOWN) now else info.lastKnownUpdate,
                lastHeartbeat = if (path == WearProtocol.HEARTBEAT_PATH) now else info.lastHeartbeat,
            )
            if (oldState != machine.wrist) store.log("Watch ${machine.wrist.name} confirmed")
            if (trustedSnapshot.state != WristState.UNKNOWN) {
                fallbackApplied = false
                applyState(trustedSnapshot.state)
            }
            persist(); scheduleDeadline()
        }
    }

    private suspend fun applyState(state: WristState) {
        if (!settings.enabled || state == WristState.UNKNOWN || lastApplied == state to settings) return
        var saved = store.savedSound.first()
        if (state == WristState.ON_WRIST && !store.ownsSound.first()) {
            saved = sound.current()
            // Commit restoration data before touching AudioManager.
            store.saveSound(saved, true)
        }
        val target = SoundPolicy.target(state, settings, sound.maximum, saved) ?: return
        sound.apply(target).onSuccess { changed ->
            if (changed) store.log("Phone ring volume -> ${target.volume}/${sound.maximum}, mode=${target.ringerMode}")
            info = info.copy(error = "")
            lastApplied = state to settings
            if (state == WristState.OFF_WRIST) store.saveSound(null, false)
        }.onFailure {
            val message = it.message ?: "Ошибка AudioManager"
            if (info.error != message) store.log(message)
            info = info.copy(error = message)
        }
    }

    suspend fun checkDeadline() {
        val probe = mutex.withLock {
            settings = store.settings.first()
            AutomationMachine.needsHeartbeatProbe(machine, settings, SystemClock.elapsedRealtime())
        }
        if (probe) {
            val physical = runCatching { transport.nearbyNodes() }
            if (!currentCoroutineContext().isActive) throw CancellationException()
            // Unknown API outcome is also given a probe grace; only proven absence skips it.
            if (physical.isFailure || physical.getOrThrow().any { it.id == settings.selectedNode }) {
                val request = mutex.withLock {
                    if (!AutomationMachine.needsHeartbeatProbe(machine, settings, SystemClock.elapsedRealtime())) null else {
                        machine = AutomationMachine.heartbeatProbe(machine, SystemClock.elapsedRealtime())
                        store.log("Heartbeat delayed: request live wrist state, wait 30 seconds before fallback")
                        persist(); scheduleDeadline()
                        settings.selectedNode
                    }
                }
                if (request != null) {
                    runCatching { transport.send(request, WearProtocol.REQUEST_PATH, byteArrayOf()) }
                        .onFailure { if (!currentCoroutineContext().isActive) throw it; store.log("Heartbeat probe request failed: ${it.message}") }
                    return
                }
            }
        }
        val verifyLoss = mutex.withLock {
            settings = store.settings.first()
            !machine.connected && AutomationMachine.deadline(machine, settings)?.let { SystemClock.elapsedRealtime() >= it } == true
        }
        if (verifyLoss) {
            val physical = runCatching { transport.nearbyNodes() }
            if (physical.isFailure) {
                if (!currentCoroutineContext().isActive) throw physical.exceptionOrNull()!!
                val deferred = mutex.withLock {
                    if (machine.connected) true
                    else if (SystemClock.elapsedRealtime() < (machine.lastKnown ?: machine.startedAt) + settings.heartbeatTimeoutSeconds * 1000L) {
                        val error = "Проверка связи: потеря не подтверждена, повтор через 5 секунд"
                        if (info.error != error) store.log(error)
                        info = info.copy(error = error)
                        persist()
                        val retryAt = SystemClock.elapsedRealtime() + 5_000
                        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, retryAt, fallbackIntent)
                        deadlineJob?.cancel()
                        deadlineJob = scope.launch { delay(5_000); checkDeadline() }
                        true
                    } else false
                }
                if (deferred) return
            } else {
                val restored = mutex.withLock {
                    val node = physical.getOrThrow().firstOrNull { settings.selectedNode.isNotEmpty() && it.id == settings.selectedNode }
                    if (node == null) false else {
                        machine = AutomationMachine.connection(machine, true, SystemClock.elapsedRealtime())
                        info = info.copy(connected = true, peerName = node.displayName)
                        store.log("Disconnect cancelled: local watch link confirmed")
                        persist(); scheduleDeadline()
                        true
                    }
                }
                if (restored) { refreshConnection(forceRequest = true); return }
            }
        }
        mutex.withLock {
        settings = store.settings.first()
        machine = AutomationMachine.tick(machine, settings, SystemClock.elapsedRealtime())
        if (machine.phase == AutomationPhase.FALLBACK_SOUND_ON && !fallbackApplied) {
            sound.apply(SoundSnapshot(sound.maximum, 2)).onSuccess {
                store.log("Fallback: phone ring volume -> MAX")
                fallbackApplied = true; lastApplied = null; info = info.copy(error = "")
            }.onFailure { info = info.copy(error = it.message ?: "Fallback failed"); store.log(info.error) }
        }
        persist()
        if (machine.phase != AutomationPhase.FALLBACK_SOUND_ON) scheduleDeadline()
        }
    }

    private suspend fun scheduleDeadline() {
        deadlineJob?.takeIf { it != currentCoroutineContext()[Job] }?.cancel(); alarms.cancel(fallbackIntent)
        val deadline = AutomationMachine.deadline(machine, settings) ?: return
        if (fallbackApplied) return
        // Allow-while-idle is inexact. UI explains Doze/OEM limits; no exact-alarm permission.
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, fallbackIntent)
        deadlineJob = scope.launch { delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)); checkDeadline() }
    }
    private suspend fun persist() {
        val deadline = AutomationMachine.deadline(machine, settings)
        info = info.copy(running = running.value, phase = if (settings.enabled) machine.phase else AutomationPhase.DISABLED,
            wrist = if (!machine.connected || machine.phase == AutomationPhase.FALLBACK_SOUND_ON) WristState.UNKNOWN else machine.wrist,
            alarmProbeKnown = if (machine.heartbeatProbeFor != null && machine.heartbeatProbeFor == machine.lastKnown) info.lastKnownUpdate else 0,
            fallbackAt = if (deadline != null) System.currentTimeMillis() + deadline - SystemClock.elapsedRealtime() else 0)
        store.writeInfo(info)
    }
    suspend fun sendConfig() {
        val value = store.settings.first()
        if (value.selectedNode.isBlank()) return
        runCatching {
            transport.send(value.selectedNode, WearProtocol.CONFIG_PATH, WearProtocol.encode(WatchConfig(value.debounceMillis, value.startOnBoot, false,
                value.offDebounceMillis, value.phoneLossAlert, value.phoneLossVoice, value.phoneLossSeconds)))
        }.onFailure { store.log("Watch config delivery deferred: ${it.message}") }
    }
    suspend fun stop(restore: Boolean, owner: String? = null) = mutex.withLock {
        if (owner != null && owner != serviceOwner) return@withLock
        settings = store.settings.first()
        deadlineJob?.cancel()
        connectionRetryJob?.cancel()
        // Unexpected service termination must not remove the process-independent safeguard.
        if (restore || !settings.enabled) alarms.cancel(fallbackIntent)
        if (restore && store.ownsSound.first()) {
            store.savedSound.first()?.let { saved ->
                sound.apply(saved).onSuccess { store.saveSound(null, false); store.log("Previous sound restored") }
                    .onFailure { info = info.copy(error = it.message ?: "Restore failed"); store.log(info.error) }
            }
        }
        running.value = false
        serviceOwner = null
        if (!settings.enabled) machine = machine.copy(phase = AutomationPhase.DISABLED)
        store.log("Phone monitor stopped"); persist()
    }
}
