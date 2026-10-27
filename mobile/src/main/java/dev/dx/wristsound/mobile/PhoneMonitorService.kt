package dev.dx.wristsound.mobile

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dev.dx.wristsound.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID

class PhoneMonitorService : Service() {
    private val serviceOwner = UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        phoneApp.scope.launch { phoneApp.store.log("Phone service error: ${error.message}") }
    })
    private var receiverRegistered = false
    private val bluetooth = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> scope.launch { phoneApp.repository.disconnected("Bluetooth disabled") }
                BluetoothAdapter.STATE_ON -> scope.launch { phoneApp.repository.refreshConnection() }
            }
        }
    }
    private val messages = MessageClient.OnMessageReceivedListener { event ->
        scope.launch { phoneApp.repository.receive(event.sourceNodeId, event.path, event.data) }
    }
    private val capabilities = CapabilityClient.OnCapabilityChangedListener {
        scope.launch { phoneApp.repository.refreshConnection() }
    }
    override fun onCreate() {
        super.onCreate()
        val started = runCatching { ServiceCompat.startForeground(this, 10,
            ServiceNotification.create(this, MainActivity::class.java, javaClass, "Контроль связи и громкости активен"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) }
        if (started.isFailure) {
            phoneApp.scope.launch {
                val message = "Foreground service rejected: ${started.exceptionOrNull()?.message}"
                phoneApp.store.log(message)
                phoneApp.store.writeInfo(phoneApp.store.info.first().copy(running = false, error = message))
            }
            stopSelf(); return
        }
        ContextCompat.registerReceiver(this, bluetooth, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        Wearable.getMessageClient(this).addListener(messages)
        Wearable.getCapabilityClient(this).addListener(capabilities, WearProtocol.WATCH_CAPABILITY)
        scope.launch {
            phoneApp.repository.begin(serviceOwner)
            phoneApp.store.settings.collect { settings ->
                if (!settings.enabled) {
                    phoneApp.repository.stop(restore = true, owner = serviceOwner)
                    stopSelf()
                } else phoneApp.repository.settingsChanged(settings)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ServiceNotification.STOP_ACTION) scope.launch {
            phoneApp.store.updateSettings { it.copy(enabled = false) }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(bluetooth)
        Wearable.getMessageClient(this).removeListener(messages)
        Wearable.getCapabilityClient(this).removeListener(capabilities, WearProtocol.WATCH_CAPABILITY)
        scope.cancel()
        phoneApp.scope.launch { phoneApp.repository.stop(restore = false, owner = serviceOwner) }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
