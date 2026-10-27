package dev.dx.wristsound.shared

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationTest {
    private val enabled = AppSettings(enabled = true)
    private fun snapshot(state: WristState, sequence: Long = 1, session: String = "a", monitoring: Boolean = true) =
        WatchSnapshot(session, sequence, state, "sensor", true, "watch", 100, monitoring)

    @Test fun unknownDoesNotChangeSound() {
        assertNull(SoundPolicy.target(WristState.UNKNOWN, enabled, 15, SoundSnapshot(9, 2)))
    }
    @Test fun preservesPreviousVolumeAndRingerMode() {
        val saved = SoundSnapshot(9, 1)
        assertEquals(saved, SoundPolicy.target(WristState.OFF_WRIST, enabled.copy(offMode = OffWristMode.RESTORE), 15, saved))
        assertEquals(SoundSnapshot(15, 2), SoundPolicy.target(WristState.OFF_WRIST, enabled, 15, saved))
    }
    @Test fun supportsUserDefinedVolumesAndVibration() {
        assertEquals(SoundSnapshot(0, 1), SoundPolicy.target(WristState.ON_WRIST, enabled.copy(onMode = OnWristMode.VIBRATE), 15, null))
        assertEquals(SoundSnapshot(8, 2), SoundPolicy.target(WristState.OFF_WRIST, enabled.copy(offMode = OffWristMode.CUSTOM, offPercent = 50), 15, null))
    }
    @Test fun reconnectDoesNotReuseOldOnWrist() {
        val on = AutomationMachine.snapshot(MachineState(), snapshot(WristState.ON_WRIST), 1000)
        val disconnected = AutomationMachine.connection(on, false, 2000)
        val connected = AutomationMachine.connection(disconnected, true, 3000)
        assertEquals(WristState.UNKNOWN, connected.wrist)
        assertEquals(AutomationPhase.UNKNOWN, connected.phase)
    }
    @Test fun disconnectDeadlineIsNotPostponedByRepeatedDisconnects() {
        val on = AutomationMachine.snapshot(MachineState(), snapshot(WristState.ON_WRIST), 1000)
        val disconnected = AutomationMachine.connection(on, false, 2000)
        val duplicate = AutomationMachine.connection(disconnected, false, 7000)
        assertEquals(17000L, AutomationMachine.deadline(duplicate, enabled))
        assertEquals(AutomationPhase.DISCONNECTED, AutomationMachine.tick(duplicate, enabled, 16999).phase)
        assertEquals(AutomationPhase.FALLBACK_SOUND_ON, AutomationMachine.tick(duplicate, enabled, 17000).phase)
    }
    @Test fun aliveButUnknownCannotKeepPhoneMutedForever() {
        val on = AutomationMachine.snapshot(MachineState(), snapshot(WristState.ON_WRIST), 1000)
        val unknown = AutomationMachine.snapshot(on, snapshot(WristState.UNKNOWN, 2), 500000)
        assertEquals(601000L, AutomationMachine.deadline(unknown, enabled))
        assertEquals(AutomationPhase.FALLBACK_SOUND_ON, AutomationMachine.tick(unknown, enabled, 601000).phase)
    }
    @Test fun settingsCanDisableFallback() {
        assertNull(AutomationMachine.deadline(MachineState(), enabled.copy(fallbackEnabled = false)))
        assertEquals(AutomationPhase.DISABLED, AutomationMachine.tick(MachineState(), AppSettings(), 10000).phase)
    }
    @Test fun rejectsDuplicatesOutOfOrderAndRetiredSessions() {
        val gate = SequenceGate()
        assertTrue(gate.accept(snapshot(WristState.ON_WRIST, 2)))
        assertFalse(gate.accept(snapshot(WristState.OFF_WRIST, 1)))
        assertFalse(gate.accept(snapshot(WristState.ON_WRIST, 2)))
        assertTrue(gate.accept(snapshot(WristState.UNKNOWN, 0, "b")))
        assertFalse(gate.accept(snapshot(WristState.ON_WRIST, 3)))
    }
    @Test fun protocolRoundTripAndMalformedRejection() {
        val original = snapshot(WristState.ON_WRIST)
        assertEquals(original, WearProtocol.decodeSnapshot(WearProtocol.encode(original)))
        assertNull(WearProtocol.decodeSnapshot(byteArrayOf(1, 2)))
        assertNull(WearProtocol.decodeSnapshot(WearProtocol.encode(original) + 0))
        assertNull(WearProtocol.decodeConfig(WearProtocol.encode(WatchConfig(0, true, true))))
        assertEquals(WatchConfig(3000, true, false), WearProtocol.decodeConfig(WearProtocol.encode(WatchConfig(3000, true, false))))
    }
    @Test fun bothDirectionsRequireStableDebounceAndDuplicatesDoNotExtendIt() = runTest {
        val source = MutableSharedFlow<WristState>()
        val values = mutableListOf<WristState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.confirmedWristState(3000).collect(values::add) }
        source.emit(WristState.ON_WRIST)
        advanceTimeBy(2000); source.emit(WristState.ON_WRIST)
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(WristState.ON_WRIST), values)
        source.emit(WristState.OFF_WRIST)
        advanceTimeBy(2000); source.emit(WristState.ON_WRIST)
        advanceTimeBy(3001); runCurrent()
        assertEquals(listOf(WristState.ON_WRIST), values)
        source.emit(WristState.OFF_WRIST)
        advanceTimeBy(3000); runCurrent()
        assertEquals(listOf(WristState.ON_WRIST, WristState.OFF_WRIST), values)
        source.emit(WristState.UNKNOWN); runCurrent()
        assertEquals(WristState.UNKNOWN, values.last())
        job.cancel()
    }
}
