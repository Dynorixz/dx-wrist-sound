package dev.dx.wristsound.shared

/** One alert per loss. A stable reconnection arms the next episode. API errors are not disconnects. */
data class PhoneLossState(
    val armed: Boolean = false,
    val connectedSince: Long? = null,
    val disconnectedAt: Long? = null,
    val alerted: Boolean = false,
)

object PhoneLossPolicy {
    const val REARM_MILLIS = 60_000L
    fun connection(old: PhoneLossState, nearby: Boolean, now: Long): PhoneLossState {
        if (nearby) {
            val since = old.connectedSince ?: now
            val rearmed = !old.alerted || now - since >= REARM_MILLIS
            return old.copy(armed = old.armed || rearmed, connectedSince = since,
                disconnectedAt = null, alerted = if (rearmed) false else old.alerted)
        }
        return old.copy(connectedSince = null, disconnectedAt = old.disconnectedAt ?: now)
    }
    fun deadline(state: PhoneLossState, settings: AppSettings): Long? =
        if (settings.phoneLossAlert && state.armed && !state.alerted) {
            state.disconnectedAt?.plus(settings.phoneLossSeconds * 1000L)
        } else null

    fun shouldAlert(state: PhoneLossState, settings: AppSettings, wrist: WristState, now: Long): Boolean =
        wrist == WristState.ON_WRIST && deadline(state, settings)?.let { now >= it } == true
}
