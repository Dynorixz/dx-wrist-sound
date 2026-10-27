package dev.dx.wristsound.shared

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat

enum class PermissionDestination { APP, NOTIFICATIONS, PHONE_LOSS_CHANNEL, DND, BATTERY, BACKGROUND_WATCH, OVERLAY, SOUND, TTS }
data class PermissionItem(val title: String, val explanation: String, val status: String, val destination: PermissionDestination)

object PermissionGuide {
    const val PHONE_LOSS_CHANNEL = "phone_loss_v1"
    fun items(context: Context, watch: Boolean, sensorPermission: String? = null): List<PermissionItem> = buildList {
        val notifications = context.getSystemService(NotificationManager::class.java)
        add(PermissionItem("Уведомления", if (watch) "Нужны для баннера «Вы забыли телефон» и уведомления фонового мониторинга. Разрешите также всплывающий показ канала «Телефон потерян»." else "Нужны для видимого уведомления фоновой автоматизации.",
            if (notifications.areNotificationsEnabled()) "Разрешены" else "Не разрешены", PermissionDestination.NOTIFICATIONS))
        if (!watch && Build.VERSION.SDK_INT >= 31) add(PermissionItem("Устройства поблизости", "Разрешите Bluetooth-доступ для контроля соединения и фонового сервиса. Он не определяет, надеты ли часы.",
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) "Разрешены" else "Нужен доступ", PermissionDestination.APP))
        add(PermissionItem("Доступ «Не беспокоить»", if (watch) "Может понадобиться только для завершения восстановления звука, изменённого старой версией. Можно выбрать звук вручную в настройках часов. В обычной работе DX не переключает их режим. Это отдельный доступ от окна над циферблатом." else "Нужен, если переключение режима звонка или восстановление звука затрагивает «Не беспокоить». Приложение не отключает ваш системный DND.",
            if (notifications.isNotificationPolicyAccessGranted) "Разрешён" else "Не предоставлен", PermissionDestination.DND))
        if (watch && sensorPermission != null) add(PermissionItem("Off-body датчик", "Эта прошивка требует доступ к датчикам тела ($sensorPermission). Разрешите его в разрешениях приложения. Используется только факт ношения; пульс не читается.",
            if (ContextCompat.checkSelfPermission(context, sensorPermission) == PackageManager.PERMISSION_GRANTED) "Разрешён" else "Нужен доступ", PermissionDestination.APP))
        add(PermissionItem(if (watch) "Работа ночью" else "Работа в фоне", if (watch) "Разрешите работу без оптимизации батареи: это позволяет восстановить мониторинг в фоне после остановки системой. Проверка выполняется примерно раз в 15 минут; точное время зависит от прошивки. После принудительной остановки откройте приложение один раз." else "Разрешите фоновую работу и автозапуск, если система останавливает приложение.",
            if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) "Без оптимизации батареи" else "Нужно отключить оптимизацию", if (watch) PermissionDestination.BACKGROUND_WATCH else PermissionDestination.BATTERY))
        if (!watch && Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")) {
            add(PermissionItem("Автозапуск Xiaomi / HyperOS", "В системных настройках: Приложения → Разрешения → Фоновый автозапуск → DX • Wrist Sound. Разрешите автозапуск. Названия разделов могут отличаться на вашей версии HyperOS.", "Проверяется вручную", PermissionDestination.APP))
        }
        if (watch) {
            add(PermissionItem("Окно поверх циферблата", "Нужно для предупреждения «Вы забыли телефон» прямо над циферблатом. На некоторых часах доступ выдаётся только через ADB с компьютера. Нажмите «Проверить доступ» для статуса и инструкции; системное меню может показывать «Недоступно» даже после выдачи доступа.",
                if (Settings.canDrawOverlays(context)) "Разрешено" else "Нужен доступ", PermissionDestination.OVERLAY))
            val channel = notifications.getNotificationChannel(PHONE_LOSS_CHANNEL)
            add(PermissionItem("Баннер потери телефона", "Разрешите всплывающий показ и вибрацию этого канала. Решение о показе баннера также зависит от DND и прошивки часов.",
                when { channel == null -> "Канал ещё не создан"; channel.importance == NotificationManager.IMPORTANCE_NONE -> "Канал выключен"; channel.importance >= NotificationManager.IMPORTANCE_HIGH -> "Высокий приоритет"; else -> "Проверьте приоритет" }, PermissionDestination.PHONE_LOSS_CHANNEL))
            add(PermissionItem("Русский голос", "Для фразы «Вы забыли телефон» установите русский офлайн-голос в системном синтезаторе речи. Интернет-голоса не используются.", "Настроить синтез речи", PermissionDestination.TTS))
            val volume = context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)
            add(PermissionItem("Громкость оповещения", "Мелодия и голос используют громкость медиа. Выберите слышимый уровень в настройках звука часов.", "Медиа: $volume", PermissionDestination.SOUND))
        }
    }

    fun open(context: Context, destination: PermissionDestination) {
        val intent = when (destination) {
            PermissionDestination.APP -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
            PermissionDestination.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            PermissionDestination.PHONE_LOSS_CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, PHONE_LOSS_CHANNEL)
            PermissionDestination.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            PermissionDestination.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            PermissionDestination.BACKGROUND_WATCH -> if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
            PermissionDestination.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
            PermissionDestination.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
            PermissionDestination.TTS -> Intent("com.android.settings.TTS_SETTINGS")
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
                .onFailure { Toast.makeText(context, "Откройте настройки приложения вручную", Toast.LENGTH_LONG).show() }
        }
    }
}
