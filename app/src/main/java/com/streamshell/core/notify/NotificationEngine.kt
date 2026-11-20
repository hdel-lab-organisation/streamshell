package com.streamshell.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamshell.core.model.AppSettings
import com.streamshell.core.watch.ChannelFeed
import com.streamshell.core.watch.FeedVideo
import com.streamshell.core.watch.NewVideoNotifications
import com.streamshell.core.watch.NewVideosEntryPoint
import com.streamshell.core.watch.WatchData
import com.streamshell.core.watch.WatchRepository
import com.streamshell.core.watch.WatchRules
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

fun AppSettings.notifyPrefs() = NotifyPrefs(
    master = notifyEnabled,
    videos = newVideoNotifications,
    live = notifyLive,
    upcoming = notifyUpcoming,
    community = false,
    favoritesOnly = notifyFavoritesOnly
)

/** What happened to the events of one run when they were handed to Android ([NotificationDispatcher.show]). */
data class Delivery(val shown: List<EventEntity>, val problem: String? = null)

/**
 * One background check (ChannelMonitor + LiveStreamMonitor + UpcomingStreamMonitor in one pass, so every channel feed
 * is read once): channels from the user's list → their public feeds → new uploads classified through their public
 * watch page → events stored in Room (one per video and kind, never notified twice) → notifications.
 */
class NotificationEngine(
    private val dao: NotifyDao,
    /** Reads one feed (tests pass a fake). */
    private val fetch: suspend (String) -> String = { url -> com.streamshell.core.watch.FeedFetch.fetch(url) },
    /** Reads a video's public watch page (tests pass a fake). */
    private val classify: (String) -> WatchPageInfo = WatchPage::info
) {
    /**
     * One run's numbers (0.7.1): shown under "Last check" and written to the diagnostics log, so that a run without
     * notifications says why.
     */
    data class Stats(
        var feedsRead: Int = 0,
        var feedsFailed: Int = 0,
        var fallbackUsed: Int = 0,
        var firstCheck: Int = 0,
        var found: Int = 0,
        var categoryOff: Int = 0,
        var channelOff: Int = 0,
        var upcomingLive: Int = 0
    ) {
        fun text(shown: Int, problem: String?): String = "feeds $feedsRead read, $feedsFailed failed" +
            (if (fallbackUsed > 0) " ($fallbackUsed via uploads list)" else "") +
            " · new $found" +
            (if (upcomingLive > 0) " + $upcomingLive streams now live" else "") +
            (if (firstCheck > 0) " · $firstCheck channels checked for the first time (only remembered)" else "") +
            (if (categoryOff > 0) " · $categoryOff category off" else "") +
            (if (channelOff > 0) " · $channelOff bell off / not a favourite" else "") +
            " · shown $shown" + (problem?.let { " · NOT SHOWN: $it" }.orEmpty())
    }

    data class Outcome(val channels: Int, val failed: Int, val stats: Stats, val delivery: Delivery)

    /**
     * One check. [deliver] shows the notifications (Android); only what it reports as shown is marked as notified, so
     * "notified" in the history and under "Last check" means Android really took the notification.
     */
    suspend fun run(
        prefs: NotifyPrefs,
        watch: WatchData,
        now: Long = System.currentTimeMillis(),
        deliver: (List<EventEntity>) -> Delivery
    ): Outcome {
        val stats = Stats()
        syncChannels(watch)
        val channels = dao.channelsNow()
        if (!prefs.master || channels.isEmpty()) return Outcome(channels.size, 0, stats, Delivery(emptyList()))
        val feeds = fetchFeeds(channels.map { it.id }, stats)
        val out = ArrayList<EventEntity>()
        var classified = 0
        channels.forEach { ch ->
            val videos = feeds[ch.id] ?: return@forEach
            val known = dao.knownVideos(ch.id).toHashSet()
            val first = ch.lastChecked == null
            val fresh = NotifyRules.newUploads(videos, known, first, now)
            // First check: remember everything the feed shows (no notification flood).
            if (first) {
                stats.firstCheck++
                videos.forEach { v -> dao.insertEvent(event(v, ch, EventKind.VIDEO, now, null)) }
            }
            fresh.forEach { v ->
                val info = if (classified < NotifyRules.MAX_CLASSIFY_PER_RUN) {
                    classified++
                    runCatching { classify(v.videoId) }.getOrDefault(WatchPageInfo.UNKNOWN)
                } else {
                    WatchPageInfo.UNKNOWN
                }
                val e = event(v, ch, info.kind, now, info.scheduledStart)
                if (dao.insertEvent(e) == -1L) return@forEach
                stats.found++
                when {
                    !prefs.wants(info.kind) -> stats.categoryOff++
                    !NotifyRules.channelNotifies(ch.bell, ch.favorite, prefs) -> stats.channelOff++
                    else -> out += e
                }
            }
            dao.checked(ch.id, now)
        }
        val live = recheckUpcoming(prefs, channels.associateBy { it.id }, now)
        stats.upcomingLive = live.size
        out += live
        val delivery = if (out.isEmpty()) Delivery(emptyList()) else runCatching { deliver(out) }.getOrElse { Delivery(emptyList(), "error: ${it.javaClass.simpleName}") }
        delivery.shown.forEach { dao.markNotified(it.videoId, it.kind, now) }
        dao.pruneEvents(now - KEEP_MS)
        dao.pruneChecks(now - KEEP_MS)
        dao.pruneSyncs(now - KEEP_MS)
        val failed = channels.size - feeds.size
        dao.insertCheck(
            CheckRunEntity(
                at = now,
                channels = channels.size,
                failed = failed,
                notified = delivery.shown.size,
                error = when {
                    feeds.isEmpty() -> "no feed could be read"
                    delivery.problem != null -> "not shown: ${delivery.problem}"
                    else -> null
                },
                found = stats.found,
                detail = stats.text(delivery.shown.size, delivery.problem)
            )
        )
        return Outcome(channels.size, failed, stats, delivery)
    }

    /** Scheduled streams around their start: live now → one "live" notification. */
    private suspend fun recheckUpcoming(prefs: NotifyPrefs, channels: Map<String, ChannelEntity>, now: Long): List<EventEntity> {
        if (!prefs.wants(EventKind.LIVE)) return emptyList()
        return dao.upcoming().filter { NotifyRules.recheckUpcoming(it.scheduledAt, now) && dao.has(it.videoId, EventKind.LIVE.name) == 0 }
            .take(NotifyRules.MAX_CLASSIFY_PER_RUN).mapNotNull { up ->
                val info = runCatching { classify(up.videoId) }.getOrDefault(WatchPageInfo.UNKNOWN)
                val ch = channels[up.channelId]
                if (!info.live || ch == null) return@mapNotNull null
                val e = up.copy(kind = EventKind.LIVE.name, discoveredAt = now, notifiedAt = null)
                if (dao.insertEvent(e) != -1L && NotifyRules.channelNotifies(ch.bell, ch.favorite, prefs)) e else null
            }
    }

    /** The user's channel list (imports + synced subscriptions, bells) mirrored into Room; favourites stay. */
    suspend fun syncChannels(watch: WatchData) {
        val existing = dao.channelsNow().associateBy { it.id }
        val ids = watch.channels.map { it.id }.toHashSet()
        dao.upsertChannels(
            watch.channels.map { c ->
                val e = existing[c.id]
                (e ?: ChannelEntity(c.id, c.title, ChannelSource.IMPORT.name)).copy(title = c.title, bell = c.id !in watch.muted)
            }
        )
        val gone = existing.keys.filter { it !in ids }
        if (gone.isNotEmpty()) dao.deleteChannels(gone)
    }

    /**
     * The channels' feeds, 6 at a time. When a channel's feed does not answer, its uploads list (the same videos, as a
     * playlist feed "UU…") is tried once — YouTube's channel feeds have had periods of errors.
     */
    private suspend fun fetchFeeds(ids: List<String>, stats: Stats): Map<String, List<FeedVideo>> = withContext(Dispatchers.IO) {
        val sem = Semaphore(PARALLEL)
        val fallback = java.util.concurrent.atomic.AtomicInteger()
        val result = coroutineScope {
            ids.map { id ->
                async {
                    sem.withPermit {
                        val main = runCatching { ChannelFeed.parse(fetch(ChannelFeed.urlOf(id))) }.getOrNull()
                        val list = main ?: NotifyRules.uploadsFeedOf(id)?.let { u ->
                            runCatching { ChannelFeed.parse(fetch(u)) }.getOrNull()?.also { fallback.incrementAndGet() }
                        }
                        list?.let { id to it }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        stats.feedsRead = result.size
        stats.feedsFailed = ids.size - result.size
        stats.fallbackUsed = fallback.get()
        result
    }

    private fun event(v: FeedVideo, ch: ChannelEntity, kind: EventKind, now: Long, scheduled: Long?) = EventEntity(
        videoId = v.videoId,
        kind = kind.name,
        channelId = ch.id,
        channelTitle = v.channel.ifBlank { ch.title },
        title = v.title,
        publishedAt = v.published,
        scheduledAt = scheduled,
        discoveredAt = now
    )

    private companion object {
        const val PARALLEL = 6
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * The public watch page of one video (www.youtube.com, HTTPS, no account, no redirects, size-limited). Sent with
 * YouTube's "consent declined" cookie (SOCS=CAI) so that the EU consent page is not shown instead — no user cookie.
 */
internal object WatchPage {
    private const val MAX_BYTES = 3_000_000
    private const val TIMEOUT_MS = 8000
    private const val HTTP_OK = 200
    private const val BUFFER = 32 * 1024

    fun info(videoId: String): WatchPageInfo {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return WatchPageInfo.UNKNOWN
        return runCatching { WatchPageInfo.parse(fetch("https://www.youtube.com/watch?v=$videoId&hl=en")) }.getOrDefault(WatchPageInfo.UNKNOWN)
    }

    private fun fetch(url: String): String? {
        val c = java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.setRequestProperty("Cookie", "SOCS=CAI")
        c.setRequestProperty("Accept-Language", "en")
        try {
            if (c.responseCode != HTTP_OK) return null
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0 || out.size() > MAX_BYTES) break
                    out.write(buf, 0, n)
                }
            }
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }
}

/** WorkManager job: runs [NotificationEngine], shows what it found. Battery-friendly: network required, no foreground service. */
class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, NewVideosEntryPoint::class.java)
        val settings = ep.settings().settings.first()
        val watch = WatchRepository(ep.libraryStore()).data.first()
        val engine = NotificationEngine(NotifyDatabase.get(applicationContext).dao())
        val r = runCatching { engine.run(settings.notifyPrefs(), watch) { NotificationDispatcher.show(applicationContext, it) } }.getOrElse {
            com.streamshell.core.diag.DiagnosticsHub.add("notify", "check failed: ${it.javaClass.simpleName} (attempt ${runAttemptCount + 1})")
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        com.streamshell.core.diag.DiagnosticsHub.add("notify", "background check: ${r.channels} channels · " + r.stats.text(r.delivery.shown.size, r.delivery.problem))
        return if (r.channels > 0 && r.failed == r.channels && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

object NotificationScheduler {
    const val UNIQUE_NAME = "notification-engine"
    private const val NOW_NAME = "notification-engine-now"
    private const val OLD_NAME = "new-videos"
    private const val BACKOFF_MIN = 15L

    fun apply(context: Context, prefs: NotifyPrefs, intervalMin: Int) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(OLD_NAME) // 0.6.7's job, replaced by this one
        if (!prefs.master) {
            wm.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<NotificationWorker>(NotifyRules.clampInterval(intervalMin).toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MIN, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** "Check now" from the notification settings. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<NotificationWorker>().setConstraints(constraints()).build()
        )
    }

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

/**
 * Notifications per kind, each in its own Android channel: "New videos" (default), "Live now" (high), "Upcoming live
 * streams" (low). One notification per event (channel, title, the video's picture, time), a summary per kind; tap opens
 * the video in StreamShell; "Watch later" for videos.
 */
object NotificationDispatcher {
    private const val CH_VIDEOS = NewVideoNotifications.CHANNEL
    private const val CH_LIVE = "live_now"
    private const val CH_UPCOMING = "upcoming_live"
    private const val GROUP = "com.streamshell.NOTIFY."
    private const val SUMMARY_BASE = 41_000
    private const val TEST_ID = 40_999
    private const val ID_MASK = 0x0FFFFFFF
    private const val INBOX_LINES = 6

    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels(nm: NotificationManager) {
        listOf(
            Triple(CH_VIDEOS, "New videos", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CH_LIVE, "Live now", NotificationManager.IMPORTANCE_HIGH),
            Triple(CH_UPCOMING, "Upcoming live streams", NotificationManager.IMPORTANCE_LOW)
        ).forEach { (id, name, imp) -> if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, imp)) }
    }

    fun channelOf(kind: EventKind) = when (kind) {
        EventKind.LIVE -> CH_LIVE
        EventKind.UPCOMING -> CH_UPCOMING
        else -> CH_VIDEOS
    }

    /**
     * Why Android would not show a notification of [kind] now, or null when it would: the permission (Android 13+),
     * notifications of the app turned off, or the kind's notification channel turned off by the user.
     */
    fun blockedReason(context: Context, kind: EventKind): String? {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return "no notification service"
        if (!allowed(context)) return "permission not granted"
        if (!nm.areNotificationsEnabled()) return "notifications of StreamShell are off in Android's settings"
        val ch = nm.getNotificationChannel(channelOf(kind))
        if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return "category \"${ch.name}\" is off in Android's settings"
        return null
    }

    fun show(context: Context, events: List<EventEntity>): Delivery {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return Delivery(emptyList(), "no notification service")
        if (!allowed(context)) return Delivery(emptyList(), "permission not granted")
        channels(nm)
        val shown = ArrayList<EventEntity>()
        val problems = LinkedHashSet<String>()
        events.groupBy { EventKind.valueOf(it.kind) }.forEach { (kind, list) ->
            val blocked = blockedReason(context, kind)
            if (blocked != null) {
                problems += blocked
                return@forEach
            }
            val sorted = list.sortedByDescending { it.publishedAt }
            sorted.forEachIndexed { i, e ->
                // More than MAX_SINGLE_PER_RUN: the rest only in the group's summary (still counted as shown).
                if (i >= NotifyRules.MAX_SINGLE_PER_RUN) {
                    shown += e
                    return@forEachIndexed
                }
                runCatching { nm.notify(idOf(e), single(context, e, kind)) }
                    .onSuccess { shown += e }
                    .onFailure { problems += "error: ${it.javaClass.simpleName}" }
            }
            runCatching { nm.notify(SUMMARY_BASE + kind.ordinal, summary(context, kind, sorted)) }
        }
        return Delivery(shown, problems.joinToString("; ").ifEmpty { null })
    }

    /** "Send test notification" in the notification settings: a real notification in the "New videos" channel. */
    fun test(context: Context): String {
        val blocked = blockedReason(context, EventKind.VIDEO)
        if (blocked != null) return "Not shown: $blocked"
        val nm = context.getSystemService(NotificationManager::class.java) ?: return "Not shown: no notification service"
        channels(nm)
        return runCatching {
            nm.notify(
                TEST_ID,
                NotificationCompat.Builder(context, CH_VIDEOS)
                    .setSmallIcon(com.streamshell.R.drawable.ic_stat_media)
                    .setContentTitle("StreamShell")
                    .setContentText("Test: notifications for new videos work.")
                    .setSubText(label(EventKind.VIDEO))
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(context, TEST_ID, Intent(context, com.streamshell.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    .build()
            )
            "Test notification sent — it should appear now."
        }.getOrElse { "Not shown: error ${it.javaClass.simpleName}" }
    }

    private fun single(context: Context, e: EventEntity, kind: EventKind): android.app.Notification {
        val url = "https://m.youtube.com/watch?v=${e.videoId}"
        val picture = runCatching { com.streamshell.core.media.Thumbnails.fetch(WatchRules.thumbnailOf(url).orEmpty()) }.getOrNull()
        val text = when (kind) {
            EventKind.LIVE -> "🔴 Live now: ${e.title}"
            EventKind.UPCOMING -> "Upcoming: ${e.title}" + (e.scheduledAt?.let { " · " + timeOf(it) }.orEmpty())
            else -> e.title
        }
        val b = NotificationCompat.Builder(context, channelOf(kind))
            .setSmallIcon(com.streamshell.R.drawable.ic_stat_media)
            .setContentTitle(e.channelTitle)
            .setContentText(text)
            .setSubText(label(kind))
            .setWhen(if (kind == EventKind.UPCOMING) e.scheduledAt ?: e.publishedAt else e.publishedAt)
            .setShowWhen(true)
            .setLargeIcon(picture)
            .setStyle(
                if (picture != null) {
                    NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as android.graphics.Bitmap?).setSummaryText(text)
                } else {
                    NotificationCompat.BigTextStyle().bigText(text)
                }
            )
            .setGroup(GROUP + kind.name)
            .setAutoCancel(true)
            .setPriority(if (kind == EventKind.LIVE) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (kind == EventKind.LIVE) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(open(context, url, idOf(e)))
        if (kind == EventKind.VIDEO) {
            val later = PendingIntent.getBroadcast(
                context,
                idOf(e),
                Intent(NewVideoNotifications.ACTION_LATER).setPackage(context.packageName)
                    .putExtra(NewVideoNotifications.EXTRA_URL, url).putExtra(NewVideoNotifications.EXTRA_TITLE, e.title).putExtra(NewVideoNotifications.EXTRA_ID, idOf(e)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(com.streamshell.R.drawable.ic_shortcut_continue, "Watch later", later)
        }
        return b.build()
    }

    private fun summary(context: Context, kind: EventKind, list: List<EventEntity>) = NotificationCompat.Builder(context, channelOf(kind))
        .setSmallIcon(com.streamshell.R.drawable.ic_stat_media)
        .setContentTitle(if (list.size == 1) list[0].channelTitle else "${list.size} × ${label(kind).lowercase()}")
        .setContentText(list.joinToString(" · ") { it.channelTitle }.take(SUMMARY_TEXT))
        .setStyle(NotificationCompat.InboxStyle().also { s -> list.take(INBOX_LINES).forEach { s.addLine("${it.channelTitle}: ${it.title}") } })
        .setGroup(GROUP + kind.name)
        .setGroupSummary(true)
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, SUMMARY_BASE + kind.ordinal, Intent(context, com.streamshell.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun open(context: Context, url: String, id: Int) = PendingIntent.getActivity(
        context,
        id,
        Intent(Intent.ACTION_VIEW, url.toUri()).setClass(context, com.streamshell.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun label(kind: EventKind) = when (kind) {
        EventKind.LIVE -> "Live now"
        EventKind.UPCOMING -> "Upcoming live stream"
        EventKind.COMMUNITY -> "Community post"
        EventKind.VIDEO -> "New video"
    }

    private fun timeOf(ms: Long): String = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        .let { java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm").format(it) }

    fun idOf(e: EventEntity): Int = NotifyRules.key(e.videoId, EventKind.valueOf(e.kind)).hashCode() and ID_MASK

    private const val SUMMARY_TEXT = 200
}
