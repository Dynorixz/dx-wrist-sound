package dev.dx.wristsound.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import dev.dx.wristsound.shared.PersistentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Locally bundled three-note chime. Uses media volume and transient audio focus. */
class LossChime(private val context: Context, private val store: PersistentStore, private val scope: CoroutineScope) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var cpu: AutoCloseable? = null
    private val timeout = Runnable { release() }
    val status = MutableStateFlow("")

    fun play() { handler.post {
        release()
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            report("Громкость медиа равна нулю: откройте настройки звука часов.")
            return@post
        }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        runCatching {
            cpu = holdWristTransitionCpu(context, 10_000)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                    if (change < 0) cancel()
                }, handler).build()
            focus = request
            check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Система не разрешила звук сейчас" }
            val sound = MediaPlayer.create(context, R.raw.phone_loss_chime, attributes, 0)
                ?: error("Не удалось открыть мелодию")
            player = sound
            sound.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            sound.setOnCompletionListener { report("Мелодия проиграна"); release() }
            sound.setOnErrorListener { _, _, _ -> report("Ошибка воспроизведения"); release(); true }
            handler.postDelayed(timeout, 8_000)
            sound.start()
            report("Мелодия оповещения")
        }.onFailure { report(it.message ?: "Ошибка звука"); release() }
    } }
    fun cancel() { handler.post { release() } }
    private fun report(message: String) { status.value = message; scope.launch { store.log(message) } }
    private fun release() {
        handler.removeCallbacks(timeout)
        player?.release(); player = null
        focus?.let(audio::abandonAudioFocusRequest); focus = null
        cpu?.close(); cpu = null
    }
}
