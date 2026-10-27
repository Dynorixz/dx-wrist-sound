package dev.dx.wristsound.wear

import android.media.AudioManager
import dev.dx.wristsound.shared.SoundSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WatchSoundControllerTest {
    @Test fun chosenMelodySurvivesRepeatedRefreshes() = runTest {
        val f = Fixture(NORMAL, 7)
        repeat(10) { assertNull(f.controller.releaseLegacyVibration()) }
        assertEquals(NORMAL, f.device.mode); assertEquals(7, f.device.volume)
        assertTrue(f.device.calls.isEmpty()); assertNull(f.store.snapshot)
    }
    @Test fun noOwnershipNeverChangesAnyUserMode() = runTest {
        for (mode in listOf(NORMAL, VIBRATE, SILENT)) {
            val f = Fixture(mode)
            assertNull(f.controller.releaseLegacyVibration())
            assertEquals(mode, f.device.mode); assertTrue(f.device.calls.isEmpty())
        }
    }
    @Test fun legacyForcedVibrationRestoresSoundOnce() = runTest {
        val f = Fixture(VIBRATE, 0, SoundSnapshot(5, NORMAL))
        assertNull(f.controller.releaseLegacyVibration())
        assertEquals(NORMAL, f.device.mode); assertEquals(5, f.device.volume)
        assertEquals(listOf("volume:5", "mode:2"), f.device.calls); assertNull(f.store.snapshot)
        f.device.mode = VIBRATE
        val count = f.device.calls.size
        repeat(5) { assertNull(f.controller.releaseLegacyVibration()) }
        assertEquals(VIBRATE, f.device.mode); assertEquals(count, f.device.calls.size)
    }
    @Test fun manualSoundAndVolumeOverrideOldSnapshot() = runTest {
        val f = Fixture(NORMAL, 8, SoundSnapshot(3, NORMAL))
        assertNull(f.controller.releaseLegacyVibration())
        assertEquals(8, f.device.volume); assertTrue(f.device.calls.isEmpty()); assertNull(f.store.snapshot)
    }
    @Test fun manualSilentChoiceIsNotOverridden() = runTest {
        val f = Fixture(SILENT, 0, SoundSnapshot(5, NORMAL))
        assertNull(f.controller.releaseLegacyVibration())
        assertEquals(SILENT, f.device.mode); assertTrue(f.device.calls.isEmpty()); assertNull(f.store.snapshot)
    }
    @Test fun obsoleteMutedOrVibrateSnapshotsNeverMuteWatch() = runTest {
        for (saved in listOf(SoundSnapshot(5, SILENT), SoundSnapshot(0, NORMAL), SoundSnapshot(5, VIBRATE))) {
            val f = Fixture(VIBRATE, saved = saved)
            assertNull(f.controller.releaseLegacyVibration())
            assertNull(f.store.snapshot); assertTrue(f.device.calls.isEmpty())
        }
    }
    @Test fun deniedRestorationRemainsRetryableWithoutSettingVibrate() = runTest {
        val original = SoundSnapshot(5, NORMAL)
        val f = Fixture(VIBRATE, saved = original)
        f.device.onMode = { throw SecurityException("Not allowed to change Do Not Disturb state") }
        assertEquals(WatchSoundProblem.POLICY_ACCESS, f.controller.releaseLegacyVibration()?.problem)
        assertEquals(original, f.store.snapshot); assertEquals(listOf("mode:2"), f.device.calls)
        f.device.onMode = null
        assertNull(f.controller.releaseLegacyVibration())
        assertEquals(NORMAL, f.device.mode); assertNull(f.store.snapshot)
    }
    @Test fun manualRecoveryAfterDenialDiscardsPendingSnapshot() = runTest {
        val f = Fixture(VIBRATE, saved = SoundSnapshot(5, NORMAL))
        f.device.onMode = { throw SecurityException("Policy denied") }
        assertNotNull(f.controller.releaseLegacyVibration())
        f.device.mode = NORMAL; f.device.volume = 9
        val count = f.device.calls.size
        assertNull(f.controller.releaseLegacyVibration())
        assertEquals(9, f.device.volume); assertEquals(count, f.device.calls.size); assertNull(f.store.snapshot)
    }
    @Test fun pendingRecoverySurvivesControllerRecreation() = runTest {
        val f = Fixture(VIBRATE, saved = SoundSnapshot(5, NORMAL))
        assertNull(WatchSoundController(f.device, f.store).releaseLegacyVibration())
        assertEquals(NORMAL, f.device.mode); assertNull(f.store.snapshot)
    }
    @Test fun fixedVolumePreservesSnapshotUntilManualRecovery() = runTest {
        val original = SoundSnapshot(5, NORMAL)
        val f = Fixture(VIBRATE, saved = original)
        f.device.fixed = true
        assertEquals(WatchSoundProblem.SYSTEM_RESTRICTION, f.controller.releaseLegacyVibration()?.problem)
        assertEquals(original, f.store.snapshot); assertTrue(f.device.calls.isEmpty())
        f.device.mode = NORMAL
        assertNull(f.controller.releaseLegacyVibration()); assertNull(f.store.snapshot)
    }
    @Test fun volumeChangeEnablingSoundDoesNotWriteModeAgain() = runTest {
        val f = Fixture(VIBRATE, 0, SoundSnapshot(5, NORMAL))
        f.device.onVolume = { volume -> f.device.volume = volume; f.device.mode = NORMAL }
        assertNull(f.controller.releaseLegacyVibration())
        assertEquals(listOf("volume:5"), f.device.calls); assertNull(f.store.snapshot)
    }
    @Test fun ignoredSystemChangeKeepsPendingRecovery() = runTest {
        val f = Fixture(VIBRATE, saved = SoundSnapshot(5, NORMAL))
        f.device.onMode = { }
        assertEquals(WatchSoundProblem.SYSTEM_RESTRICTION, f.controller.releaseLegacyVibration()?.problem)
        assertNotNull(f.store.snapshot)
    }
    private class Fixture(mode: Int, volume: Int = 5, saved: SoundSnapshot? = null) {
        val device = Device(mode, volume)
        val store = Store(saved)
        val controller = WatchSoundController(device, store)
    }
    private class Store(var snapshot: SoundSnapshot?) : WatchSoundStore {
        override suspend fun saved() = snapshot
        override suspend fun save(snapshot: SoundSnapshot?) { this.snapshot = snapshot }
    }
    private class Device(var mode: Int, var volume: Int) : WatchSoundDevice {
        var fixed = false
        val calls = mutableListOf<String>()
        var onMode: ((Int) -> Unit)? = null
        var onVolume: ((Int) -> Unit)? = null
        override val ringerMode get() = mode
        override val ringVolume get() = volume
        override val hasPolicyAccess get() = false
        override val isVolumeFixed get() = fixed
        override fun changeMode(mode: Int) {
            calls += "mode:$mode"; onMode?.invoke(mode) ?: run { this.mode = mode }
        }
        override fun changeVolume(volume: Int) {
            calls += "volume:$volume"; onVolume?.invoke(volume) ?: run { this.volume = volume }
        }
    }
    companion object {
        private const val SILENT = AudioManager.RINGER_MODE_SILENT
        private const val VIBRATE = AudioManager.RINGER_MODE_VIBRATE
        private const val NORMAL = AudioManager.RINGER_MODE_NORMAL
    }
}
