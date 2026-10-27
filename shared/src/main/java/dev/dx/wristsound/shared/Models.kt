package dev.dx.wristsound.shared

enum class WristState { ON_WRIST, OFF_WRIST, UNKNOWN }
enum class OnWristMode { SILENT, VIBRATE, CUSTOM }
enum class OffWristMode { MAXIMUM, RESTORE, CUSTOM }
enum class AutomationPhase { DISABLED, UNKNOWN, WATCH_ON_WRIST, WATCH_OFF_WRIST, DISCONNECTED, FALLBACK_SOUND_ON }

data class AppSettings(
    val enabled: Boolean = false,
    val onMode: OnWristMode = OnWristMode.VIBRATE,
    val offMode: OffWristMode = OffWristMode.MAXIMUM,
    val onPercent: Int = 0,
    val offPercent: Int = 75,
    val fallbackEnabled: Boolean = true,
    val disconnectSeconds: Int = 15,
    val heartbeatTimeoutSeconds: Int = 600,
    val debounceMillis: Long = 3_000,
    val startOnBoot: Boolean = true,
    /** Legacy configuration field; watch sound is now controlled by its system settings. */
    val watchVibration: Boolean = false,
    val selectedNode: String = "",
    val offDebounceMillis: Long = 5_000,
    val phoneLossAlert: Boolean = true,
    val phoneLossVoice: Boolean = true,
    val phoneLossSeconds: Int = 30,
    val alertSettingsPending: Boolean = false,
)

data class SoundSnapshot(val volume: Int, val ringerMode: Int)
data class WatchSnapshot(
    val session: String,
    val sequence: Long,
    val state: WristState,
    val sensorName: String,
    val sensorAvailable: Boolean,
    val watchName: String,
    val detectedAt: Long,
    val monitoring: Boolean,
)

data class WatchConfig(
    val debounceMillis: Long, val startOnBoot: Boolean, val vibration: Boolean,
    val offDebounceMillis: Long = 5_000,
    val phoneLossAlert: Boolean = true, val phoneLossVoice: Boolean = true,
    val phoneLossSeconds: Int = 30,
)
data class EventEntry(val time: Long, val message: String)

/** Pure policy: UNKNOWN never directly changes audio; availability is a separate signal. */
object SoundPolicy {
    fun target(state: WristState, settings: AppSettings, max: Int, previous: SoundSnapshot?): SoundSnapshot? =
        when (state) {
            WristState.UNKNOWN -> null
            WristState.ON_WRIST -> when (settings.onMode) {
                OnWristMode.SILENT -> SoundSnapshot(0, 0)
                OnWristMode.VIBRATE -> SoundSnapshot(0, 1)
                OnWristMode.CUSTOM -> SoundSnapshot(percent(settings.onPercent, max), if (settings.onPercent == 0) 0 else 2)
            }
            WristState.OFF_WRIST -> when (settings.offMode) {
                OffWristMode.MAXIMUM -> SoundSnapshot(max, 2)
                OffWristMode.RESTORE -> previous ?: SoundSnapshot(max, 2)
                OffWristMode.CUSTOM -> SoundSnapshot(percent(settings.offPercent, max), if (settings.offPercent == 0) 0 else 2)
            }
        }

    private fun percent(percent: Int, max: Int) = ((percent.coerceIn(0, 100) * max + 50) / 100).coerceIn(0, max)
}

/** All deadlines use local elapsedRealtime, never the watch's wall clock. */
data class MachineState(
    val phase: AutomationPhase = AutomationPhase.UNKNOWN,
    val wrist: WristState = WristState.UNKNOWN,
    val connected: Boolean = false,
    val disconnectedAt: Long? = null,
    val lastAlive: Long? = null,
    val lastKnown: Long? = null,
    val startedAt: Long = 0,
    val heartbeatProbeAt: Long? = null,
    val heartbeatProbeFor: Long? = null,
)

object AutomationMachine {
    fun connection(old: MachineState, connected: Boolean, now: Long): MachineState {
        if (old.connected == connected && (connected || old.disconnectedAt != null)) return old
        return old.copy(
            connected = connected,
            disconnectedAt = if (connected) null else now,
            // Reconnect cannot reapply an old ON_WRIST until a live snapshot arrives.
            wrist = if (connected) WristState.UNKNOWN else old.wrist,
            phase = if (connected) AutomationPhase.UNKNOWN else AutomationPhase.DISCONNECTED,
        )
    }

    fun snapshot(old: MachineState, snapshot: WatchSnapshot, now: Long): MachineState = old.copy(
        connected = true, disconnectedAt = null, lastAlive = now,
        lastKnown = if (snapshot.state != WristState.UNKNOWN && snapshot.monitoring) now else old.lastKnown,
        heartbeatProbeAt = if (snapshot.state != WristState.UNKNOWN && snapshot.monitoring) null else old.heartbeatProbeAt,
        heartbeatProbeFor = if (snapshot.state != WristState.UNKNOWN && snapshot.monitoring) null else old.heartbeatProbeFor,
        wrist = snapshot.state,
        phase = when (snapshot.state) {
            WristState.ON_WRIST -> AutomationPhase.WATCH_ON_WRIST
            WristState.OFF_WRIST -> AutomationPhase.WATCH_OFF_WRIST
            WristState.UNKNOWN -> AutomationPhase.UNKNOWN
        },
    )

    fun deadline(state: MachineState, settings: AppSettings): Long? {
        if (!settings.enabled || !settings.fallbackEnabled) return null
        val originalStaleAt = (state.lastKnown ?: state.startedAt) + settings.heartbeatTimeoutSeconds * 1000L
        val staleAt = if (state.heartbeatProbeAt != null && state.heartbeatProbeFor == state.lastKnown) {
            maxOf(originalStaleAt, state.heartbeatProbeAt + HEARTBEAT_PROBE_MILLIS)
        } else originalStaleAt
        val disconnectedAt = state.disconnectedAt?.plus(settings.disconnectSeconds * 1000L)
        return if (disconnectedAt != null) minOf(staleAt, disconnectedAt) else staleAt
    }

    const val HEARTBEAT_PROBE_MILLIS = 30_000L
    fun needsHeartbeatProbe(state: MachineState, settings: AppSettings, now: Long): Boolean =
        settings.enabled && settings.fallbackEnabled && state.connected && state.lastKnown != null &&
            state.heartbeatProbeFor != state.lastKnown &&
            now >= state.lastKnown + settings.heartbeatTimeoutSeconds * 1000L

    fun heartbeatProbe(state: MachineState, now: Long) = state.copy(heartbeatProbeAt = now, heartbeatProbeFor = state.lastKnown)

    fun tick(state: MachineState, settings: AppSettings, now: Long): MachineState {
        if (!settings.enabled) return state.copy(phase = AutomationPhase.DISABLED)
        val deadline = deadline(state, settings) ?: return state
        return if (now >= deadline) state.copy(phase = AutomationPhase.FALLBACK_SOUND_ON) else state
    }
}

class SequenceGate {
    private var session = ""
    private var sequence = -1L
    private val retired = ArrayDeque<String>()
    fun accept(snapshot: WatchSnapshot): Boolean {
        if (snapshot.session in retired) return false
        if (snapshot.session == session && snapshot.sequence <= sequence) return false
        if (snapshot.session != session) {
            if (session.isNotEmpty()) retired.addLast(session)
            if (retired.size > 8) retired.removeFirst()
            session = snapshot.session
        }
        sequence = snapshot.sequence
        return true
    }
}
