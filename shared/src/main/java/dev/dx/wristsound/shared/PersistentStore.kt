package dev.dx.wristsound.shared

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class DeviceInfo(
    val running: Boolean = false,
    val connected: Boolean = false,
    val peerName: String = "Часы не найдены",
    val wrist: WristState = WristState.UNKNOWN,
    val sensor: String = "Не получен",
    val sensorAvailable: Boolean = false,
    val lastUpdate: Long = 0,
    val lastHeartbeat: Long = 0,
    val phase: AutomationPhase = AutomationPhase.UNKNOWN,
    val error: String = "",
    val fallbackAt: Long = 0,
    val phoneLost: Boolean = false,
    val lastKnownUpdate: Long = 0,
    val alarmProbeKnown: Long = 0,
)

class PersistentStore(context: Context, scope: CoroutineScope) {
    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        migrations = listOf(SoundProfileMigration),
        scope = scope,
        produceFile = { context.preferencesDataStoreFile("wrist_sound") },
    )
    private val data = dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    val settings: Flow<AppSettings> = data.map { p ->
        AppSettings(
            enabled = p[booleanPreferencesKey("enabled")] ?: false,
            onMode = enumValue(p[stringPreferencesKey("on_mode")], OnWristMode.VIBRATE),
            offMode = enumValue(p[stringPreferencesKey("off_mode")], OffWristMode.MAXIMUM),
            onPercent = (p[intPreferencesKey("on_percent")] ?: 0).coerceIn(0, 100),
            offPercent = (p[intPreferencesKey("off_percent")] ?: 75).coerceIn(0, 100),
            fallbackEnabled = p[booleanPreferencesKey("fallback")] ?: true,
            disconnectSeconds = (p[intPreferencesKey("disconnect")] ?: 15).coerceIn(5, 120),
            heartbeatTimeoutSeconds = (p[intPreferencesKey("heartbeat_timeout")] ?: 600).coerceIn(300, 1800),
            debounceMillis = (p[longPreferencesKey("debounce")] ?: 3000).coerceIn(1000, 10000),
            startOnBoot = p[booleanPreferencesKey("boot")] ?: true,
            watchVibration = false,
            selectedNode = p[stringPreferencesKey("selected_node")] ?: "",
            offDebounceMillis = (p[longPreferencesKey("off_debounce")] ?: 5000).coerceIn(3000, 15000),
            phoneLossAlert = p[booleanPreferencesKey("phone_loss_alert")] ?: true,
            phoneLossVoice = p[booleanPreferencesKey("phone_loss_voice")] ?: true,
            phoneLossSeconds = (p[intPreferencesKey("phone_loss_seconds")] ?: 30).coerceIn(15, 120),
            alertSettingsPending = p[booleanPreferencesKey("alert_settings_pending")] ?: false,
        )
    }
    val info: Flow<DeviceInfo> = data.map { p ->
        runCatching {
            val json = JSONObject(p[stringPreferencesKey("info")] ?: "{}")
            DeviceInfo(
                running = json.optBoolean("running"), connected = json.optBoolean("connected"),
                peerName = json.optString("peer", "Часы не найдены"),
                wrist = enumValue(json.optString("wrist"), WristState.UNKNOWN),
                sensor = json.optString("sensor", "Не получен"), sensorAvailable = json.optBoolean("sensor_available"),
                lastUpdate = json.optLong("update"), lastHeartbeat = json.optLong("heartbeat"),
                phase = enumValue(json.optString("phase"), AutomationPhase.UNKNOWN), error = json.optString("error"),
                fallbackAt = json.optLong("fallback_at"),
                phoneLost = json.optBoolean("phone_lost"),
                lastKnownUpdate = json.optLong("known_update"),
                alarmProbeKnown = json.optLong("alarm_probe_known"),
            )
        }.getOrDefault(DeviceInfo())
    }
    val events: Flow<List<EventEntry>> = data.map { parseEvents(it[stringPreferencesKey("events")]) }
    val savedSound: Flow<SoundSnapshot?> = data.map { p ->
        p[intPreferencesKey("saved_volume")]?.let { SoundSnapshot(it, p[intPreferencesKey("saved_mode")] ?: 2) }
    }
    val ownsSound: Flow<Boolean> = data.map { it[booleanPreferencesKey("owns_sound")] ?: false }

    suspend fun updateSettings(change: (AppSettings) -> AppSettings) {
        // edit serializes concurrent UI changes; derive from the transaction's current preferences.
        dataStore.edit { p ->
            val current = settingsFrom(p)
            val value = change(current)
            p[booleanPreferencesKey("enabled")] = value.enabled
            p[stringPreferencesKey("on_mode")] = value.onMode.name
            p[stringPreferencesKey("off_mode")] = value.offMode.name
            p[intPreferencesKey("on_percent")] = value.onPercent.coerceIn(0, 100)
            p[intPreferencesKey("off_percent")] = value.offPercent.coerceIn(0, 100)
            p[booleanPreferencesKey("fallback")] = value.fallbackEnabled
            p[intPreferencesKey("disconnect")] = value.disconnectSeconds.coerceIn(5, 120)
            p[intPreferencesKey("heartbeat_timeout")] = value.heartbeatTimeoutSeconds.coerceIn(300, 1800)
            p[longPreferencesKey("debounce")] = value.debounceMillis.coerceIn(1000, 10000)
            p[booleanPreferencesKey("boot")] = value.startOnBoot
            p[booleanPreferencesKey("watch_vibration")] = false
            p[stringPreferencesKey("selected_node")] = value.selectedNode
            p[longPreferencesKey("off_debounce")] = value.offDebounceMillis.coerceIn(3000, 15000)
            p[booleanPreferencesKey("phone_loss_alert")] = value.phoneLossAlert
            p[booleanPreferencesKey("phone_loss_voice")] = value.phoneLossVoice
            p[intPreferencesKey("phone_loss_seconds")] = value.phoneLossSeconds.coerceIn(15, 120)
            p[booleanPreferencesKey("alert_settings_pending")] = value.alertSettingsPending
        }
    }

    private fun settingsFrom(p: Preferences) = AppSettings(
        p[booleanPreferencesKey("enabled")] ?: false,
        enumValue(p[stringPreferencesKey("on_mode")], OnWristMode.VIBRATE),
        enumValue(p[stringPreferencesKey("off_mode")], OffWristMode.MAXIMUM),
        p[intPreferencesKey("on_percent")] ?: 0, p[intPreferencesKey("off_percent")] ?: 75,
        p[booleanPreferencesKey("fallback")] ?: true, p[intPreferencesKey("disconnect")] ?: 15,
        p[intPreferencesKey("heartbeat_timeout")] ?: 600, p[longPreferencesKey("debounce")] ?: 3000,
        p[booleanPreferencesKey("boot")] ?: true, false,
        p[stringPreferencesKey("selected_node")] ?: "",
        p[longPreferencesKey("off_debounce")] ?: 5000,
        p[booleanPreferencesKey("phone_loss_alert")] ?: true,
        p[booleanPreferencesKey("phone_loss_voice")] ?: true,
        p[intPreferencesKey("phone_loss_seconds")] ?: 30,
        p[booleanPreferencesKey("alert_settings_pending")] ?: false,
    )

    suspend fun writeInfo(info: DeviceInfo) {
        val json = JSONObject().put("running", info.running).put("connected", info.connected)
            .put("peer", info.peerName).put("wrist", info.wrist.name).put("sensor", info.sensor)
            .put("sensor_available", info.sensorAvailable).put("update", info.lastUpdate).put("heartbeat", info.lastHeartbeat)
            .put("phase", info.phase.name).put("error", info.error).put("fallback_at", info.fallbackAt)
            .put("phone_lost", info.phoneLost)
            .put("known_update", info.lastKnownUpdate)
            .put("alarm_probe_known", info.alarmProbeKnown)
        dataStore.edit { it[stringPreferencesKey("info")] = json.toString() }
    }

    suspend fun saveSound(snapshot: SoundSnapshot?, owned: Boolean) {
        dataStore.edit { p ->
            if (snapshot != null) {
                p[intPreferencesKey("saved_volume")] = snapshot.volume
                p[intPreferencesKey("saved_mode")] = snapshot.ringerMode
            } else {
                p.remove(intPreferencesKey("saved_volume")); p.remove(intPreferencesKey("saved_mode"))
            }
            p[booleanPreferencesKey("owns_sound")] = owned
        }
    }
    suspend fun log(message: String) {
        Log.i("DXWristSound", message)
        dataStore.edit { p ->
            val entries = (parseEvents(p[stringPreferencesKey("events")]) + EventEntry(System.currentTimeMillis(), message)).takeLast(100)
            p[stringPreferencesKey("events")] = JSONArray().apply {
                entries.forEach { put(JSONObject().put("time", it.time).put("message", it.message)) }
            }.toString()
        }
    }
    suspend fun clearLog() { dataStore.edit { it.remove(stringPreferencesKey("events")) } }
    private fun parseEvents(value: String?): List<EventEntry> = runCatching {
        val array = JSONArray(value ?: "[]")
        (0 until array.length()).map { array.getJSONObject(it).let { entry -> EventEntry(entry.getLong("time"), entry.getString("message")) } }
    }.getOrDefault(emptyList())
    private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T =
        runCatching { enumValueOf<T>(value ?: "") }.getOrDefault(default)
}
