package dev.dx.wristsound.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import dev.dx.wristsound.shared.*
import dev.dx.wristsound.shared.R as SharedR
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhoneLossMonitor(
    private val context: Context,
    private val repository: WatchRepository,
    private val store: PersistentStore,
    private val transport: LocalWearTransport,
    private val speech: OfflineSpeech,
    private val chime: LossChime,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var gate = PhoneLossState()
    private var pending: Job? = null
    private var rearm: Job? = null
    private var showing = false
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val popup = PhoneLossPopup(context, store, scope)
    init { createChannel(context) }

    fun start() = scope.launch {
        combine(store.settings.distinctUntilChanged(), repository.wrist, repository.phoneNearby) { settings, wrist, nearby ->
            Triple(settings, wrist, nearby)
        }.collect { (settings, wrist, nearby) -> mutex.withLock {
            if (nearby != null) gate = PhoneLossPolicy.connection(gate, nearby, SystemClock.elapsedRealtime())
            pending?.cancel(); pending = null
            rearm?.cancel(); rearm = null
            if (nearby == true || !settings.phoneLossAlert) clear()
            if (nearby == true && gate.alerted) rearm = scope.launch {
                delay(PhoneLossPolicy.REARM_MILLIS)
                mutex.withLock {
                    if (repository.phoneNearby.value == true) gate = PhoneLossPolicy.connection(gate, true, SystemClock.elapsedRealtime())
                }
            }
            val deadline = PhoneLossPolicy.deadline(gate, settings)
            if (nearby == false && wrist == WristState.ON_WRIST && deadline != null) schedule((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        } }
    }

    private fun schedule(delayMillis: Long, holdCpu: Boolean = true) {
        // Confirm this short, exceptional disconnect even if the display sleeps during debounce.
        val cpu = if (holdCpu) holdWristTransitionCpu(context, delayMillis + 15_000) else null
        pending = scope.launch { delay(delayMillis); confirmLoss() }.also { it.invokeOnCompletion { cpu?.close() } }
    }
    private suspend fun confirmLoss() = mutex.withLock {
        val settings = store.settings.first()
        if (!PhoneLossPolicy.shouldAlert(gate, settings, repository.wrist.value, SystemClock.elapsedRealtime())) return@withLock
        val physical = runCatching { transport.nearbyNodes() }
        if (physical.isFailure) {
            if (!currentCoroutineContext().isActive) throw physical.exceptionOrNull()!!
            store.log("Phone loss not confirmed: Data Layer check failed; retry in 10 seconds")
            schedule(10_000, holdCpu = false) // An API outage must not hold CPU indefinitely.
            return@withLock
        }
        if (physical.getOrThrow().any { it.id == settings.selectedNode }) {
            gate = PhoneLossPolicy.connection(gate, true, SystemClock.elapsedRealtime())
            repository.notePhoneConnection(true)
            clear()
            return@withLock
        }
        gate = gate.copy(alerted = true)
        showing = true
        repository.markPhoneLost(true)
        store.log("Phone loss confirmed after ${settings.phoneLossSeconds}s: Вы забыли телефон")
        showNotification()
        popup.show { scope.launch { dismiss() } }
        if (settings.phoneLossVoice) { chime.cancel(); speech.speak("Вы забыли телефон") }
        else { speech.cancel(); chime.play() }
    }
    suspend fun dismiss() = mutex.withLock { clear() }
    private suspend fun clear() {
        notifications.cancel(NOTIFICATION_ID)
        popup.hide()
        if (showing) {
            showing = false
            speech.cancel()
            chime.cancel()
            repository.markPhoneLost(false)
        }
    }
    fun close() {
        pending?.cancel(); rearm?.cancel()
        notifications.cancel(NOTIFICATION_ID)
        popup.hide()
        speech.cancel()
        chime.cancel()
    }
    private suspend fun showNotification() {
        if (!notifications.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            store.log("Phone-loss banner blocked: allow notifications in the Permissions screen")
            return
        }
        val open = PendingIntent.getActivity(context, 91, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismiss = PendingIntent.getService(context, 92, Intent(context, WristMonitorService::class.java).setAction(DISMISS_ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val picture = createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(context, SharedR.drawable.lost_phone)?.apply { setBounds(0, 0, 240, 240); draw(Canvas(picture)) }
        notifications.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, PermissionGuide.PHONE_LOSS_CHANNEL)
            .setSmallIcon(SharedR.drawable.ic_wrist).setLargeIcon(picture)
            .setContentTitle("Вы забыли телефон")
            .setContentText("Связь с телефоном потеряна. Проверьте, где он остался.")
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText("Связь с телефоном потеряна"))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setLocalOnly(true)
            .setTimeoutAfter(300_000).addAction(0, "Понятно", dismiss).build())
    }
    companion object {
        const val DISMISS_ACTION = "dev.dx.wristsound.DISMISS_PHONE_LOSS"
        private const val NOTIFICATION_ID = 90
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(PermissionGuide.PHONE_LOSS_CHANNEL, "Телефон потерян", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Подтверждённая потеря локальной связи с телефоном, когда часы на руке"
                    enableVibration(true); vibrationPattern = longArrayOf(0, 250, 150, 250)
                    setSound(null, null)
                })
        }
    }
}
