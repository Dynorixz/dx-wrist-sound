package dev.dx.wristsound.shared

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast

data class AppLink(val title: String, val url: String)
object AppLinks {
    fun author(context: Context) = listOf(
        AppLink("Telegram · @${context.getString(R.string.author_name)}", context.getString(R.string.author_telegram)),
        AppLink("GitHub · ${context.getString(R.string.author_name)}", context.getString(R.string.author_github)),
        AppLink("Поддержать разработчика", context.getString(R.string.author_support)),
    )
    fun open(context: Context, link: AppLink) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        }.onFailure { Toast.makeText(context, "Нет приложения для ссылки. Она доступна в «О приложении» на телефоне.", Toast.LENGTH_LONG).show() }
    }
}
