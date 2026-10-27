package dev.dx.wristsound.shared

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.dx.wristsound.shared.R

object ServiceNotification {
    const val STOP_ACTION = "dev.dx.wristsound.STOP"
    private const val CHANNEL = "wrist_automation"
    fun create(context: Context, activity: Class<*>, service: Class<*>, text: String): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Автоматизация звука", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) },
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, activity), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(context, 1, Intent(context, service).setAction(STOP_ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_wrist)
            .setContentTitle("DX • Wrist Sound").setContentText(text).setContentIntent(open)
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
    }
}
