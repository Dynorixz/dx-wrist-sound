package dev.dx.wristsound.shared

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SoundProfileMigrationTest {
    @Test fun defaultProfileVibratesPhoneOnWristAndMakesItAudibleOffWrist() {
        val settings = AppSettings()
        assertEquals(OnWristMode.VIBRATE, settings.onMode)
        assertEquals(OffWristMode.MAXIMUM, settings.offMode)
        assertFalse(settings.watchVibration)
        assertEquals(SoundSnapshot(0, 1), SoundPolicy.target(WristState.ON_WRIST, settings, 7, null))
        assertEquals(SoundSnapshot(7, 2), SoundPolicy.target(WristState.OFF_WRIST, settings, 7, null))
        assertNull(SoundPolicy.target(WristState.UNKNOWN, settings, 7, null))
    }
    @Test fun upgradeReplacesOldSilentAndForcedWatchVibrationProfile() = runTest {
        val before = mutablePreferencesOf(stringPreferencesKey("on_mode") to "SILENT",
            stringPreferencesKey("off_mode") to "RESTORE", booleanPreferencesKey("watch_vibration") to true)
        assertTrue(SoundProfileMigration.shouldMigrate(before))
        val after = SoundProfileMigration.migrate(before)
        assertEquals("VIBRATE", after[stringPreferencesKey("on_mode")])
        assertEquals("MAXIMUM", after[stringPreferencesKey("off_mode")])
        assertEquals(false, after[booleanPreferencesKey("watch_vibration")])
        assertFalse(SoundProfileMigration.shouldMigrate(after))
    }
    @Test fun upgradePreservesMonitoringPairingAlertsAndLegacyRecoverySnapshot() = runTest {
        val before = mutablePreferencesOf(booleanPreferencesKey("enabled") to true,
            stringPreferencesKey("selected_node") to "paired-watch", booleanPreferencesKey("phone_loss_voice") to false,
            intPreferencesKey("phone_loss_seconds") to 75, longPreferencesKey("off_debounce") to 9000L,
            booleanPreferencesKey("owns_sound") to true, intPreferencesKey("saved_volume") to 5,
            intPreferencesKey("saved_mode") to 2)
        val after = SoundProfileMigration.migrate(before)
        for ((key, value) in before.asMap()) assertEquals(value, after.asMap()[key])
    }
    @Test fun migrationRunsOnceAndDoesNotUndoLaterPhoneChoices() = runTest {
        val first = SoundProfileMigration.migrate(emptyPreferences())
        val customised = first.toMutablePreferences().apply {
            this[stringPreferencesKey("on_mode")] = "CUSTOM"
            this[stringPreferencesKey("off_mode")] = "RESTORE"
        }
        assertFalse(SoundProfileMigration.shouldMigrate(customised))
        assertEquals(customised, SoundProfileMigration.migrate(customised))
    }
    @Test fun freshPreferencesReceiveSameProfileAsUpgrade() = runTest {
        val after = SoundProfileMigration.migrate(emptyPreferences())
        assertEquals(AppSettings().onMode.name, after[stringPreferencesKey("on_mode")])
        assertEquals(AppSettings().offMode.name, after[stringPreferencesKey("off_mode")])
        assertEquals(false, after[booleanPreferencesKey("watch_vibration")])
    }
}
