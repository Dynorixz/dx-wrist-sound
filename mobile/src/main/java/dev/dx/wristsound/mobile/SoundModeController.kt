package dev.dx.wristsound.mobile

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import dev.dx.wristsound.shared.SoundSnapshot

class SoundModeController(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    val maximum: Int get() = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
    val policyAccess: Boolean get() = notifications.isNotificationPolicyAccessGranted
    val isDndActive: Boolean get() = notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    fun current() = SoundSnapshot(audio.getStreamVolume(AudioManager.STREAM_RING), audio.ringerMode)

    fun apply(target: SoundSnapshot): Result<Boolean> = runCatching {
        check(!audio.isVolumeFixed) { "На этом устройстве громкость зафиксирована системой" }
        val wanted = target.copy(volume = target.volume.coerceIn(0, maximum))
        val before = current()
        if (before == wanted) return@runCatching false
        if (before.ringerMode != wanted.ringerMode &&
            (before.ringerMode == AudioManager.RINGER_MODE_SILENT || wanted.ringerMode == AudioManager.RINGER_MODE_SILENT)) {
            check(policyAccess) { "Предоставьте доступ «Не беспокоить» для переключения беззвучного режима" }
        }
        // Do not change the global interruption filter: Android 15+ limits that API.
        if (audio.ringerMode != wanted.ringerMode) audio.ringerMode = wanted.ringerMode
        if (audio.getStreamVolume(AudioManager.STREAM_RING) != wanted.volume) {
            audio.setStreamVolume(AudioManager.STREAM_RING, wanted.volume, 0)
        }
        check(current() == wanted) { "Система не применила громкость: проверьте DND и ограничения производителя" }
        true
    }
}
