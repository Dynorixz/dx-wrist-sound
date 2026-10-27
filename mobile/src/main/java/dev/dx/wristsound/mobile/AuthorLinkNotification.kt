package dev.dx.wristsound.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.dx.wristsound.shared.AppLink
import dev.dx.wristsound.shared.R as SharedR

object AuthorLinkNotification {
    fun show(context: Context, link: AppLink): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return false
        val channel = "author_links"
        manager.createNotificationChannel(NotificationChannel(channel, "Ссылки с часов", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 44,
            Intent(Intent.ACTION_VIEW, link.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(44, NotificationCompat.Builder(context, channel).setSmallIcon(SharedR.drawable.ic_wrist)
            .setContentTitle(link.title).setContentText("Нажмите, чтобы открыть ссылку, выбранную на часах")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setLocalOnly(true).build())
        return true
    }
}
