package dev.dx.wristsound.wear

import android.app.Service
import android.app.NotificationManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dev.dx.wristsound.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

class WristMonitorService : Service() {
    private var phoneLoss: PhoneLossMonitor? = null
    private var monitoring: Job? = null
    private val sensorRevision = MutableStateFlow(0)
    private var lastWakeRefresh = 0L
    private val wakeEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED) {
                scope.launch { watchApp.repository.refreshSound() }
                return
            }
            // Some OEMs discard sensor subscriptions overnight. A display wake restores it too.
            val now = SystemClock.elapsedRealtime()
            if (now - lastWakeRefresh > 5 * 60_000) {
                lastWakeRefresh = now
                sensorRevision.value++
            }
        }
    }
    private val serviceOwner = UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        watchApp.scope.launch { watchApp.store.log("Watch service error: ${error.message}") }
    })
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            val callback = this
            scope.launch {
                try { watchApp.repository.publish() }
                finally { if (watchApp.repository.running.value) handler.postDelayed(callback, WearProtocol.HEARTBEAT_INTERVAL) }
            }
        }
    }
    private val messages = MessageClient.OnMessageReceivedListener { event ->
        scope.launch { watchApp.repository.receive(event.sourceNodeId, event.path, event.data) }
    }
    private val capabilities = CapabilityClient.OnCapabilityChangedListener {
        scope.launch { watchApp.repository.publish(WearProtocol.WRIST_PATH) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        val started = runCatching { ServiceCompat.startForeground(this, 20,
            ServiceNotification.create(this, MainActivity::class.java, javaClass, "Определение состояния запястья активно"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) }
        if (started.isFailure) {
            watchApp.scope.launch {
                val message = "Watch foreground service rejected: ${started.exceptionOrNull()?.message}"
                watchApp.store.log(message)
                watchApp.store.writeInfo(watchApp.store.info.first().copy(running = false, error = message))
            }
            stopSelf(); return
        }
        Wearable.getMessageClient(this).addListener(messages)
        Wearable.getCapabilityClient(this).addListener(capabilities, WearProtocol.PHONE_CAPABILITY)
        ContextCompat.registerReceiver(this, wakeEvents,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        startSensorMonitoring()
        handler.postDelayed(heartbeat, WearProtocol.HEARTBEAT_INTERVAL)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startSensorMonitoring() {
        monitoring = scope.launch {
            if (!watchApp.store.settings.first().enabled) { MonitorRecovery.cancel(this@WristMonitorService); stopSelf(); return@launch }
            MonitorRecovery.schedule(this@WristMonitorService)
            watchApp.repository.begin(serviceOwner)
            phoneLoss = PhoneLossMonitor(applicationContext, watchApp.repository, watchApp.store, watchApp.transport, watchApp.speech, watchApp.chime, scope)
                .also { it.start() }
            // Observe disable only after begin, including a rapid toggle during startup.
            launch {
                watchApp.store.settings.collect { settings ->
                    if (!settings.enabled) {
                        MonitorRecovery.cancel(this@WristMonitorService)
                        watchApp.repository.stop(serviceOwner)
                        stopSelf()
                    }
                }
            }
            if (!watchApp.detector.descriptor.value.available) {
                watchApp.store.log("No supported off-body sensor; monitoring disabled")
                watchApp.store.updateSettings { it.copy(enabled = false) }
                stopSelf(); return@launch
            }
            combine(watchApp.store.settings.map { it.debounceMillis to it.offDebounceMillis }.distinctUntilChanged(), sensorRevision) { delays, revision -> delays to revision }
                .flatMapLatest { (delays, _) ->
                watchApp.detector.readings.onEach { watchApp.store.log("${it.state.name} detected") }
                    .confirmedWristReadings(delays.first, delays.second).onEach { reading ->
                try {
                    if (reading.state == WristState.UNKNOWN || watchApp.repository.wrist.value != reading.state) {
                        watchApp.repository.confirmed(reading.state)
                    }
                    if (!watchApp.detector.descriptor.value.available || watchApp.detector.descriptor.value.error.isNotEmpty()) {
                        watchApp.store.log(watchApp.detector.descriptor.value.error.ifBlank { "Off-body sensor unavailable" })
                    }
                } finally {
                    // Delivery completes before releasing CPU; also covers exceptions, stop and bounces.
                    reading.close()
                }
            }.recoveringSubscription { error ->
                watchApp.store.log("Подписка на датчик прервана; повтор через 30 сек: ${error?.message ?: watchApp.detector.descriptor.value.error}")
            }
            }.collect()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ServiceNotification.STOP_ACTION) scope.launch { watchApp.store.updateSettings { it.copy(enabled = false) } }
        if (intent?.action == PhoneLossMonitor.DISMISS_ACTION) scope.launch { phoneLoss?.dismiss() }
        if (intent?.action == RECHECK_ACTION) {
            if (monitoring?.isActive == true) sensorRevision.value++
            else { phoneLoss?.close(); startSensorMonitoring() }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        runCatching { unregisterReceiver(wakeEvents) }
        phoneLoss?.close()
        Wearable.getMessageClient(this).removeListener(messages)
        Wearable.getCapabilityClient(this).removeListener(capabilities, WearProtocol.PHONE_CAPABILITY)
        scope.cancel()
        watchApp.scope.launch {
            watchApp.repository.stop(serviceOwner)
            if (watchApp.store.settings.first().enabled) MonitorRecovery.schedule(this@WristMonitorService)
            else MonitorRecovery.cancel(this@WristMonitorService)
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val RECHECK_ACTION = "dev.dx.wristsound.RECHECK_SENSOR" }
}
