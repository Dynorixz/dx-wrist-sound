package dev.dx.wristsound.shared

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegressionTest {
    private val settings = AppSettings(enabled = true, phoneLossSeconds = 30)
    private fun loss() = PhoneLossPolicy.connection(PhoneLossPolicy.connection(PhoneLossState(), true, 0), false, 1000)

    @Test fun bootWithoutPhoneMustNotSayYouForgotIt() {
        val state = PhoneLossPolicy.connection(PhoneLossState(), false, 0)
        assertNull(PhoneLossPolicy.deadline(state, settings))
        assertFalse(PhoneLossPolicy.shouldAlert(state, settings, WristState.ON_WRIST, 600_000))
    }
    @Test fun shortDisconnectAndReconnectionCancelPendingAlert() {
        val state = PhoneLossPolicy.connection(loss(), true, 20_000)
        assertNull(PhoneLossPolicy.deadline(state, settings))
        assertFalse(PhoneLossPolicy.shouldAlert(state, settings, WristState.ON_WRIST, 60_000))
    }
    @Test fun phoneLossNeedsFullDelayAndOnWristConfirmation() {
        val state = loss()
        assertFalse(PhoneLossPolicy.shouldAlert(state, settings, WristState.ON_WRIST, 30_999))
        assertTrue(PhoneLossPolicy.shouldAlert(state, settings, WristState.ON_WRIST, 31_000))
        assertFalse(PhoneLossPolicy.shouldAlert(state, settings, WristState.OFF_WRIST, 40_000))
        assertFalse(PhoneLossPolicy.shouldAlert(state, settings, WristState.UNKNOWN, 40_000))
    }
    @Test fun repeatedDisconnectDoesNotRestartConfirmation() {
        assertEquals(31_000L, PhoneLossPolicy.deadline(PhoneLossPolicy.connection(loss(), false, 20_000), settings))
    }
    @Test fun alertCanBeDisabledIndependentlyOfSoundAutomation() {
        assertFalse(PhoneLossPolicy.shouldAlert(loss(), settings.copy(phoneLossAlert = false), WristState.ON_WRIST, 50_000))
    }
    @Test fun flappingConnectionDoesNotRepeatVoice() {
        var state = loss().copy(alerted = true)
        state = PhoneLossPolicy.connection(state, true, 40_000)
        state = PhoneLossPolicy.connection(state, false, 50_000)
        assertFalse(PhoneLossPolicy.shouldAlert(state, settings, WristState.ON_WRIST, 90_000))
    }
    @Test fun stableReconnectArmsNextLossEpisode() {
        var state = loss().copy(alerted = true)
        state = PhoneLossPolicy.connection(state, true, 40_000)
        state = PhoneLossPolicy.connection(state, true, 100_000)
        state = PhoneLossPolicy.connection(state, false, 101_000)
        assertTrue(PhoneLossPolicy.shouldAlert(state, settings, WristState.ON_WRIST, 131_000))
    }
    @Test fun newSettingsRoundTripWithoutDroppingAlertOptions() {
        val config = WatchConfig(4000, false, false, 9000, true, false, 75)
        assertEquals(config, WearProtocol.decodeConfig(WearProtocol.encode(config)))
        assertNull(WearProtocol.decodeConfig(WearProtocol.encode(config.copy(phoneLossSeconds = 1))))
    }
    @Test fun previousVersionConfigurationStillDecodes() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { it.writeInt(1); it.writeLong(3000); it.writeBoolean(true); it.writeBoolean(false) }
        assertEquals(WatchConfig(3000, true, false), WearProtocol.decodeConfig(output.toByteArray()))
    }
    @Test fun delayedHeartbeatGetsOneProbeGraceAndThenFallback() {
        val snapshot = WatchSnapshot("session", 1, WristState.ON_WRIST, "sensor", true, "watch", 1, true)
        val live = AutomationMachine.snapshot(MachineState(), snapshot, 1_000)
        assertTrue(AutomationMachine.needsHeartbeatProbe(live, settings, 601_000))
        val probing = AutomationMachine.heartbeatProbe(live, 601_000)
        assertEquals(631_000L, AutomationMachine.deadline(probing, settings))
        assertFalse(AutomationMachine.needsHeartbeatProbe(probing, settings, 640_000))
        assertEquals(AutomationPhase.WATCH_ON_WRIST, AutomationMachine.tick(probing, settings, 630_999).phase)
        assertEquals(AutomationPhase.FALLBACK_SOUND_ON, AutomationMachine.tick(probing, settings, 631_000).phase)
    }
    @Test fun freshProbeResponseCancelsFallbackWithoutTrustingUnknown() {
        val snapshot = WatchSnapshot("session", 1, WristState.ON_WRIST, "sensor", true, "watch", 1, true)
        val probing = AutomationMachine.heartbeatProbe(AutomationMachine.snapshot(MachineState(), snapshot, 1_000), 601_000)
        val unknown = AutomationMachine.snapshot(probing, snapshot.copy(state = WristState.UNKNOWN), 610_000)
        assertEquals(631_000L, AutomationMachine.deadline(unknown, settings))
        val fresh = AutomationMachine.snapshot(probing, snapshot.copy(sequence = 2), 610_000)
        assertEquals(1_210_000L, AutomationMachine.deadline(fresh, settings))
        assertNull(fresh.heartbeatProbeAt)
    }
    @Test fun longerOffWristConfirmationRejectsFourSecondSensorGlitch() = runTest {
        val sensor = MutableSharedFlow<WristState>()
        val received = mutableListOf<WristState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sensor.confirmedWristState(3000, 5000).collect(received::add)
        }
        sensor.emit(WristState.ON_WRIST); advanceTimeBy(3000); runCurrent()
        sensor.emit(WristState.OFF_WRIST); advanceTimeBy(4000)
        sensor.emit(WristState.ON_WRIST); advanceTimeBy(5000); runCurrent()
        assertEquals(listOf(WristState.ON_WRIST), received)
        sensor.emit(WristState.OFF_WRIST); advanceTimeBy(5000); runCurrent()
        assertEquals(listOf(WristState.ON_WRIST, WristState.OFF_WRIST), received)
    }
}
