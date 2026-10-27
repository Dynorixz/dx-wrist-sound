package dev.dx.wristsound.wear

import android.content.Context
import android.os.PowerManager
import android.util.Log

/** Does not wake the display or dismiss the PIN screen. There is no idle/service-long wake lock. */
internal fun holdWristTransitionCpu(context: Context, timeoutMillis: Long = 75_000L): AutoCloseable {
    val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DXWristSound:wrist-transition")
    lock.setReferenceCounted(false)
    try {
        // Max debounce (15s), a preceding transport operation and the new delivery; hard safety cap.
        lock.acquire(timeoutMillis)
    } catch (error: RuntimeException) {
        Log.e("DXWristSound", "Could not hold CPU for wrist transition", error)
    }
    return AutoCloseable { if (lock.isHeld) lock.release() }
}
