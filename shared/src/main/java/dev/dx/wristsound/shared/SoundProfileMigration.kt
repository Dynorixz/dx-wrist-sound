package dev.dx.wristsound.shared

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.*

/** One-time change to phone vibration on wrist / audible phone off wrist. */
object SoundProfileMigration : DataMigration<Preferences> {
    private val version = intPreferencesKey("sound_profile_version")
    override suspend fun shouldMigrate(currentData: Preferences) = (currentData[version] ?: 0) < 1
    override suspend fun migrate(currentData: Preferences): Preferences {
        if (!shouldMigrate(currentData)) return currentData
        return currentData.toMutablePreferences().apply {
            this[stringPreferencesKey("on_mode")] = OnWristMode.VIBRATE.name
            this[stringPreferencesKey("off_mode")] = OffWristMode.MAXIMUM.name
            this[booleanPreferencesKey("watch_vibration")] = false
            this[version] = 1
        }
    }
    override suspend fun cleanUp() = Unit
}
