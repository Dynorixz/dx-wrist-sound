package dev.dx.wristsound.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringRecoveryTest {
    @Test fun registrationFailureRetriesWithoutDisablingMonitoring() = runTest {
        var subscriptions = 0
        val errors = mutableListOf<Throwable?>()
        val received = mutableListOf<WristState>()
        val source = flow {
            if (++subscriptions == 1) error("OEM temporarily rejected subscription")
            emit(WristState.OFF_WRIST)
            awaitCancellation()
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.recoveringSubscription(onInterrupted = { errors += it }).collect { received += it }
        }
        assertEquals(1, subscriptions)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(1, subscriptions)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, subscriptions)
        assertEquals(listOf(WristState.OFF_WRIST), received)
        assertEquals(1, errors.size)
    }
    @Test fun silentlyCompletedSensorSubscriptionIsRecreated() = runTest {
        var subscriptions = 0
        var completed = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow { subscriptions++; emit(WristState.UNKNOWN) }
                .recoveringSubscription(onInterrupted = { if (it == null) completed++ }).collect()
        }
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, subscriptions)
        assertEquals(2, completed)
    }
    @Test fun disablingDuringBackoffDoesNotRestartSubscription() = runTest {
        var subscriptions = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow<WristState> { subscriptions++; error("sensor") }
                .recoveringSubscription(onInterrupted = {}).collect()
        }
        job.cancelAndJoin()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, subscriptions)
    }
    @Test fun processingFailureCanRetryButDoesNotLeakTransitionCpu() = runTest {
        var held = 0
        var attempts = 0
        val received = mutableListOf<WristState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow {
                held++
                emit(WristReading(WristState.OFF_WRIST, AutoCloseable { held-- }))
                awaitCancellation()
            }.onEach { reading ->
                try { if (++attempts == 1) error("temporary delivery failure") }
                finally { reading.close() }
            }.recoveringSubscription(onInterrupted = {}).collect { received += it.state }
        }
        assertEquals(0, held)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(WristState.OFF_WRIST), received)
        assertEquals(0, held)
    }
    @Test fun downstreamFailurePropagatesRatherThanSpinningForever() = runTest {
        var interruptions = 0
        val result = runCatching {
            flowOf(1).recoveringSubscription(onInterrupted = { interruptions++ }).collect { error("consumer failed") }
        }
        assertEquals("consumer failed", result.exceptionOrNull()?.message)
        assertEquals(0, interruptions)
    }
}
