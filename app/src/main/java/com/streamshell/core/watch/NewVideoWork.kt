package com.streamshell.core.watch

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.streamshell.core.settings.SettingsRepository
import com.streamshell.di.LibraryStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NewVideosEntryPoint {
    fun settings(): SettingsRepository

    @LibraryStore
    fun libraryStore(): DataStore<Preferences>
}

/** Downloads a channel feed (www.youtube.com/feeds/videos.xml only, HTTPS, no cookies, size-limited). */
internal object FeedFetch {
    private const val MAX_BYTES = 1_500_000
    private const val TIMEOUT_MS = 8000

    fun fetch(url: String): String {
        val u = java.net.URL(url)
        require(u.protocol == "https" && u.host == "www.youtube.com" && u.path == "/feeds/videos.xml")
        val c = u.openConnection() as javax.net.ssl.HttpsURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = false
        c.useCaches = false
        try {
            check(c.responseCode == HTTP_OK) { "HTTP ${c.responseCode}" }
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    check(out.size() <= MAX_BYTES) { "too large" }
                }
            }
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    private const val HTTP_OK = 200
    private const val BUFFER = 16 * 1024
}

/** Shared names of the new-video notifications (the engine is core.notify.NotificationEngine since 0.7.0). */
object NewVideoNotifications {
    const val CHANNEL = "new_videos"
    const val ACTION_LATER = "com.streamshell.action.NEW_VIDEO_LATER"
    const val EXTRA_URL = "url"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ID = "id"
}

/** "Watch later" from a new-video notification: saved without opening the app. */
class NewVideoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NewVideoNotifications.ACTION_LATER) return
        val url = intent.getStringExtra(NewVideoNotifications.EXTRA_URL)?.takeIf { it.startsWith("https://m.youtube.com/watch?v=") } ?: return
        val title = intent.getStringExtra(NewVideoNotifications.EXTRA_TITLE).orEmpty()
        val id = intent.getIntExtra(NewVideoNotifications.EXTRA_ID, 0)
        val pending = goAsync()
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, NewVideosEntryPoint::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                WatchRepository(ep.libraryStore()).update { w ->
                    if (w.later.any { it.url == url }) w else w.copy(later = WatchRules.toggleLater(w.later, url, title, System.currentTimeMillis()))
                }
            }
            context.getSystemService(NotificationManager::class.java)?.cancel(id)
            pending.finish()
        }
    }
}
