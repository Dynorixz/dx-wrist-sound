package dev.dx.wristsound.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.dx.wristsound.shared.PersistentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Lazy offline TTS. Never changes media volume or DND; releases engine and focus after speech. */
class OfflineSpeech(context: Context, private val store: PersistentStore, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var engine: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private var generation = 0
    private var timeout: Runnable? = null
    private var cpu: AutoCloseable? = null
    val status = MutableStateFlow("")

    fun speak(text: String) = handler.post {
        release()
        val token = ++generation
        cpu = holdWristTransitionCpu(context, 30_000)
        status.value = "Подготовка офлайн-голоса…"
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            fail("Громкость медиа на часах равна нулю. Откройте разрешения → Громкость оповещения.")
            return@post
        }
        timeout = Runnable { if (generation == token) fail("Синтезатор не ответил. Установите русский офлайн-голос в настройках речи.") }
            .also { handler.postDelayed(it, 25_000) }
        runCatching {
            engine = TextToSpeech(context) { result -> handler.post initialized@{
                if (generation != token) return@initialized
                val tts = engine ?: return@initialized
                if (result != TextToSpeech.SUCCESS) { fail("Системный синтезатор речи недоступен."); return@initialized }
                val voice = tts.voices?.filter { it.locale.language == "ru" && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in (it.features ?: emptySet()) }
                    ?.maxByOrNull { it.quality }
                if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                    fail("Русский офлайн-голос не установлен. Откройте разрешения → Русский голос."); return@initialized
                }
                tts.setAudioAttributes(attributes)
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) cancel()
                    }, handler).build()
                focus = request
                if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    fail("Система не разрешила голосовое уведомление сейчас."); return@initialized
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { status.value = "Голосовое уведомление" }
                    override fun onDone(id: String?) = finish(token, "Голосовое уведомление произнесено")
                    @Deprecated("Legacy engine callback")
                    override fun onError(id: String?) = finish(token, "Ошибка синтеза речи")
                    override fun onError(id: String?, errorCode: Int) = finish(token, "Ошибка синтеза речи: $errorCode")
                })
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "phone-loss-$token") == TextToSpeech.ERROR) fail("Не удалось произнести уведомление.")
            } }
        }.onFailure { fail("Ошибка синтезатора: ${it.message}") }
    }
    private fun finish(token: Int, message: String) { handler.post {
        if (generation == token) { status.value = message; scope.launch { store.log(message) }; release() }
    } }
    private fun fail(message: String) {
        status.value = message
        scope.launch { store.log(message) }
        ++generation
        release()
    }
    fun cancel() { handler.post { ++generation; release() } }
    private fun release() {
        timeout?.let(handler::removeCallbacks); timeout = null
        engine?.stop(); engine?.shutdown(); engine = null
        focus?.let(audio::abandonAudioFocusRequest); focus = null
        cpu?.close(); cpu = null
    }
}
