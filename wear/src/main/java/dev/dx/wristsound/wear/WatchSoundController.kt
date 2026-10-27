package dev.dx.wristsound.wear

import android.media.AudioManager
import dev.dx.wristsound.shared.SoundSnapshot
import java.util.concurrent.CancellationException

enum class WatchSoundProblem(val message: String) {
    POLICY_ACCESS("Часы запретили завершить восстановление звука после старой версии."),
    SYSTEM_RESTRICTION("Не удалось завершить восстановление звука. Включите звук в настройках часов."),
}

data class WatchSoundFailure(val problem: WatchSoundProblem, val diagnostic: String)

interface WatchSoundDevice {
    val ringerMode: Int
    val ringVolume: Int
    val hasPolicyAccess: Boolean
    val isVolumeFixed: Boolean
    fun changeMode(mode: Int)
    fun changeVolume(volume: Int)
}

interface WatchSoundStore {
    /** Return a snapshot only while the app owns the sound change. */
    suspend fun saved(): SoundSnapshot?
    suspend fun save(snapshot: SoundSnapshot?)
}

/** Only releases vibration imposed by older versions. Never acquires sound ownership. */
class WatchSoundController(private val device: WatchSoundDevice, private val store: WatchSoundStore) {
    suspend fun releaseLegacyVibration(): WatchSoundFailure? {
        val saved = store.saved() ?: return null
        // A manual sound choice takes precedence over the obsolete snapshot.
        // Old SILENT/zero-volume snapshots must never mute the watch again.
        if (device.ringerMode != AudioManager.RINGER_MODE_VIBRATE ||
            saved.ringerMode != AudioManager.RINGER_MODE_NORMAL || saved.volume <= 0) {
            store.save(null)
            return null
        }
        if (device.isVolumeFixed) return WatchSoundFailure(WatchSoundProblem.SYSTEM_RESTRICTION, "Volume is fixed")
        val failure = attempt {
            // The only system mode this migration may write is NORMAL.
            if (device.ringVolume != saved.volume) device.changeVolume(saved.volume)
            if (device.ringerMode != AudioManager.RINGER_MODE_NORMAL) device.changeMode(AudioManager.RINGER_MODE_NORMAL)
            check(current() == saved) { "Saved sound was not restored" }
        }
        if (failure == null) store.save(null)
        return failure
    }

    private fun current() = SoundSnapshot(device.ringVolume, device.ringerMode)
    private inline fun attempt(change: () -> Unit): WatchSoundFailure? = try {
        change(); null
    } catch (error: SecurityException) {
        WatchSoundFailure(if (device.hasPolicyAccess) WatchSoundProblem.SYSTEM_RESTRICTION else WatchSoundProblem.POLICY_ACCESS,
            "${error.javaClass.simpleName}: ${error.message}")
    } catch (error: RuntimeException) {
        if (error is CancellationException) throw error
        WatchSoundFailure(WatchSoundProblem.SYSTEM_RESTRICTION, "${error.javaClass.simpleName}: ${error.message}")
    }
}
