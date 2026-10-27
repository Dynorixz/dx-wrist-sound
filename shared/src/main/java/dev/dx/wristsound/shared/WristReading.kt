package dev.dx.wristsound.shared

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce

/** Owns CPU time from the synchronous sensor callback until confirmation and delivery finish. */
class WristReading(val state: WristState, private val lease: AutoCloseable? = null) : AutoCloseable {
    private val completed = AtomicBoolean(false)
    override fun close() {
        if (completed.compareAndSet(false, true)) lease?.close()
    }
}

/** Each sensor subscription owns its leases, so late completion cannot release a newer event. */
class PendingWristReadings(private val acquire: () -> AutoCloseable) : AutoCloseable {
    private var previous: WristState? = null
    private var pending: WristReading? = null

    @Synchronized fun capture(state: WristState): WristReading? {
        if (state == previous) return null
        // Acquire the replacement BEFORE releasing the previous event: no suspend gap on a bounce.
        val reading = WristReading(state, acquire())
        pending?.close()
        pending = reading
        previous = state
        return reading
    }

    @Synchronized override fun close() {
        pending?.close()
        pending = null
        previous = null
    }
}

@OptIn(FlowPreview::class)
fun Flow<WristReading>.confirmedWristReadings(onDelayMillis: Long, offDelayMillis: Long): Flow<WristReading> =
    // PendingWristReadings already removes duplicate raw callbacks. Every emitted lease needs cleanup,
    // including UNKNOWN following the initial synthetic UNKNOWN and a bounce back to confirmed ON.
    debounce {
        when (it.state) {
            WristState.UNKNOWN -> 0
            WristState.ON_WRIST -> onDelayMillis
            WristState.OFF_WRIST -> offDelayMillis
        }
    }
