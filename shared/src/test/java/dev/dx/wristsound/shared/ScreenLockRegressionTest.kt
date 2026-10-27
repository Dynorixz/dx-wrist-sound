package dev.dx.wristsound.shared

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Tests the CPU-lease/debounce/delivery contract, not a simulated Android PIN implementation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenLockRegressionTest {
    private class Cpu {
        var held = 0
        var acquisitions = 0
        val awake get() = held > 0
        fun acquire(): AutoCloseable {
            held++
            acquisitions++
            return AutoCloseable { held--; check(held >= 0) }
        }
    }

    @Test fun removedWatchKeepsCpuAwakeThroughDebounceAndAsyncDelivery() = runTest {
        val cpu = Cpu()
        val source = PendingWristReadings(cpu::acquire)
        val events = MutableSharedFlow<WristReading>()
        val sent = mutableListOf<WristState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.confirmedWristReadings(3_000, 5_000).collect { reading ->
                try {
                    assertTrue(cpu.awake)
                    delay(6_000) // Capability discovery + asynchronous phone message.
                    assertTrue(cpu.awake)
                    sent += reading.state
                } finally { reading.close() }
            }
        }
        val removed = source.capture(WristState.OFF_WRIST)!!
        assertTrue(cpu.awake) // Acquired before the synchronous sensor callback returns.
        events.emit(removed)
        advanceTimeBy(4_999); runCurrent()
        assertTrue(cpu.awake); assertTrue(sent.isEmpty())
        advanceTimeBy(5_001); runCurrent()
        assertTrue(cpu.awake); assertTrue(sent.isEmpty())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(WristState.OFF_WRIST), sent)
        assertFalse(cpu.awake)
        source.close()
        assertEquals(0, cpu.held)
    }

    @Test fun bounceBackToLastConfirmedStateStillReleasesThePendingLease() = runTest {
        val cpu = Cpu()
        val source = PendingWristReadings(cpu::acquire)
        val events = MutableSharedFlow<WristReading>()
        val confirmed = mutableListOf<WristState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.confirmedWristReadings(3_000, 5_000).collect {
                confirmed += it.state
                it.close()
            }
        }
        events.emit(source.capture(WristState.ON_WRIST)!!)
        advanceTimeBy(3_000); runCurrent()
        events.emit(source.capture(WristState.OFF_WRIST)!!)
        advanceTimeBy(4_000); runCurrent()
        events.emit(source.capture(WristState.ON_WRIST)!!)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf(WristState.ON_WRIST, WristState.ON_WRIST), confirmed)
        assertFalse(cpu.awake)
        source.close()
    }

    @Test fun duplicateRawCallbacksDoNotExtendConfirmationOrAcquireExtraCpuTime() = runTest {
        val cpu = Cpu()
        val source = PendingWristReadings(cpu::acquire)
        val events = MutableSharedFlow<WristReading>()
        val confirmed = mutableListOf<WristState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.confirmedWristReadings(3_000, 5_000).collect {
                confirmed += it.state; it.close()
            }
        }
        events.emit(source.capture(WristState.OFF_WRIST)!!)
        advanceTimeBy(4_000); runCurrent()
        assertNull(source.capture(WristState.OFF_WRIST))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(WristState.OFF_WRIST), confirmed)
        assertEquals(1, cpu.acquisitions)
        assertFalse(cpu.awake)
        source.close()
    }

    @Test fun completionOfOldDeliveryDoesNotReleaseTheNewTransition() {
        val cpu = Cpu()
        val source = PendingWristReadings(cpu::acquire)
        val first = source.capture(WristState.ON_WRIST)!!
        val second = source.capture(WristState.OFF_WRIST)!!
        first.close()
        assertEquals(1, cpu.held)
        second.close(); second.close(); source.close()
        assertFalse(cpu.awake)
    }

    @Test fun stopDuringDebounceReleasesCpuAndLaterCleanupIsSafe() {
        val cpu = Cpu()
        val source = PendingWristReadings(cpu::acquire)
        val reading = source.capture(WristState.OFF_WRIST)!!
        source.close()
        reading.close()
        assertFalse(cpu.awake)
    }

    @Test fun failedDeliveryReleasesCpuInFinally() = runTest {
        val cpu = Cpu()
        val source = PendingWristReadings(cpu::acquire)
        val reading = source.capture(WristState.OFF_WRIST)!!
        runCatching {
            try {
                delay(5_000)
                assertTrue(cpu.awake)
                error("Phone disconnected during delivery")
            } finally { reading.close() }
        }
        assertFalse(cpu.awake)
        source.close()
    }

    @Test fun oldSubscriptionCleanupCannotReleaseCpuHeldByNewSubscription() {
        val cpu = Cpu()
        val old = PendingWristReadings(cpu::acquire)
        val current = PendingWristReadings(cpu::acquire)
        val oldReading = old.capture(WristState.OFF_WRIST)!!
        val currentReading = current.capture(WristState.ON_WRIST)!!
        old.close(); oldReading.close()
        assertEquals(1, cpu.held)
        currentReading.close(); current.close()
        assertFalse(cpu.awake)
    }

    @Test fun unknownSensorValueAfterStartupDoesNotLeaveCpuHeld() = runTest {
        val cpu = Cpu()
        val source = PendingWristReadings(cpu::acquire)
        val events = MutableSharedFlow<WristReading>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.confirmedWristReadings(3_000, 5_000).collect { it.close() }
        }
        events.emit(WristReading(WristState.UNKNOWN))
        events.emit(source.capture(WristState.UNKNOWN)!!)
        runCurrent()
        assertFalse(cpu.awake)
        source.close()
    }
}
