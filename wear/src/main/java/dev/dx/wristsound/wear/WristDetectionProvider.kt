package dev.dx.wristsound.wear

import android.content.Context
import android.Manifest
import android.app.KeyguardManager
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dev.dx.wristsound.shared.PendingWristReadings
import dev.dx.wristsound.shared.WristReading
import dev.dx.wristsound.shared.WristState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

data class SensorDescriptor(val name: String, val available: Boolean, val wakeUp: Boolean,
    val permissionNeeded: String? = null, val error: String = "")
interface WristDetectionProvider {
    val readings: Flow<WristReading>
    val descriptor: StateFlow<SensorDescriptor>
}

object WristProviderFactory {
    fun create(context: Context): WristDetectionProvider {
        val manager = context.getSystemService(SensorManager::class.java)
        val candidates = manager.getSensorList(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT).sortedByDescending { it.isWakeUpSensor }
        return if (candidates.isEmpty()) UnsupportedWristProvider() else OffBodySensorProvider(context.applicationContext, manager, candidates)
    }
}

private class UnsupportedWristProvider : WristDetectionProvider {
    override val descriptor = MutableStateFlow(SensorDescriptor("Off-body sensor отсутствует", false, false))
    override val readings = flowOf(WristReading(WristState.UNKNOWN))
}

private class OffBodySensorProvider(
    private val context: Context,
    private val manager: SensorManager,
    private val candidates: List<Sensor>,
) : WristDetectionProvider {
    override val descriptor = MutableStateFlow(candidates.first().let { SensorDescriptor(it.name, true, it.isWakeUpSensor) })
    override val readings: Flow<WristReading> = callbackFlow {
        val pending = PendingWristReadings { holdWristTransitionCpu(context) }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        trySend(WristReading(WristState.UNKNOWN))
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val state = when (event.values.firstOrNull()) {
                    1f -> WristState.ON_WRIST
                    0f -> WristState.OFF_WRIST
                    else -> WristState.UNKNOWN
                }
                // A wake-up sensor only wakes us for its callback, not for the asynchronous debounce.
                val reading = pending.capture(state) ?: return
                Log.i("DXWristSound", "${state.name} raw; wake-up=${event.sensor.isWakeUpSensor}; PIN locked=${keyguard.isDeviceLocked}")
                if (trySend(reading).isFailure) reading.close()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        var registrationError: Throwable? = null
        val selected = candidates.firstOrNull { sensor ->
            runCatching { manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0) }
                .onFailure { registrationError = it }.getOrDefault(false)
        }
        if (selected == null) {
            val sensor = candidates.first()
            val permission = if (registrationError is SecurityException || Build.MANUFACTURER.equals("samsung", true)) {
                if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
            } else null
            descriptor.value = SensorDescriptor(sensor.name, true, sensor.isWakeUpSensor, permission,
                "Не удалось зарегистрировать датчик: ${registrationError?.message ?: "система отказала в подписке"}")
            trySend(WristReading(WristState.UNKNOWN))
            pending.close()
            close()
            return@callbackFlow
        } else descriptor.value = SensorDescriptor(selected.name, true, selected.isWakeUpSensor)
        awaitClose {
            manager.unregisterListener(listener)
            pending.close()
        }
    }
}
