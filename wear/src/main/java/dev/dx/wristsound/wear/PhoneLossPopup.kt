package dev.dx.wristsound.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.dx.wristsound.shared.PersistentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A bounded overlay above the watch face; no accessibility service or PIN bypass. */
class PhoneLossPopup(private val context: Context, private val store: PersistentStore, private val scope: CoroutineScope) {
    private val handler = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var preparation: Job? = null
    private var revision = 0L
    private val timeout = Runnable { remove() }

    fun show(onDismiss: () -> Unit) { handler.post {
        remove()
        if (!Settings.canDrawOverlays(context)) {
            scope.launch { store.log("Нет доступа к окну над циферблатом: Разрешения → Окно поверх циферблата → Проверить доступ. На некоторых часах нужен ADB.") }
            return@post
        }
        val request = revision
        // Keep the brief IO-to-window handoff awake even if the sound cannot start.
        val cpu = holdWristTransitionCpu(context, 10_000)
        preparation = scope.launch {
            val photograph = withContext(Dispatchers.IO) { decodePhotograph() }
            withContext(Dispatchers.Main.immediate) {
                if (request != revision || !Settings.canDrawOverlays(context)) return@withContext
                preparation = null
                mount(photograph, onDismiss)
            }
        }.also { it.invokeOnCompletion { cpu.close() } }
    } }
    private fun mount(photograph: Bitmap?, onDismiss: () -> Unit) {
        val root = PhoneLossAlertView(context, photograph) { remove(); onDismiss() }
        @Suppress("DEPRECATION") // An overlay has no Activity on which to call setTurnScreenOn.
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.OPAQUE).apply { gravity = Gravity.CENTER; preferredRefreshRate = 60f }
        runCatching { windows.addView(root, params); view = root; handler.postDelayed(timeout, 30_000) }
            .onFailure { scope.launch { store.log("Окно потери телефона недоступно: ${it.message}") } }
    }
    private fun decodePhotograph(): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, R.drawable.phone_left_background, probe)
        val metrics = context.resources.displayMetrics
        val target = maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(320, 768)
        var sample = 1
        while (maxOf(probe.outWidth, probe.outHeight) / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeResource(context.resources, R.drawable.phone_left_background,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }
    fun hide() { handler.post { remove() } }
    private fun remove() {
        revision++
        preparation?.cancel(); preparation = null
        handler.removeCallbacks(timeout)
        view?.let { runCatching { windows.removeViewImmediate(it) } }; view = null
    }
}
