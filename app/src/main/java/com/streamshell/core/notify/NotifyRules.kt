package com.streamshell.core.notify

import com.streamshell.core.watch.FeedVideo

/**
 * StreamShell Notification Engine (0.7.0) — pure rules, unit-tested. The engine's data lives in Room ([NotifyDatabase]);
 * preferences in DataStore (AppSettings.notify*); the work runs in WorkManager ([NotificationWorker]).
 *
 * Data sources (no server, no API key): YouTube's public channel feeds for new uploads; the public watch page of a new
 * upload to tell video, live stream and scheduled stream apart; the user's subscriptions from YouTube's own page in the
 * signed-in browser ([SubscriptionSyncManager]). Community posts have no reliable source and are not fetched.
 */
enum class EventKind { VIDEO, LIVE, UPCOMING, COMMUNITY }

enum class ChannelSource { IMPORT, SYNC, BOTH }

/** What a public watch page says about a video ([WatchPageInfo.parse]). */
data class WatchPageInfo(val live: Boolean, val upcoming: Boolean, val scheduledStart: Long?, val known: Boolean) {
    companion object {
        val UNKNOWN = WatchPageInfo(live = false, upcoming = false, scheduledStart = null, known = false)

        /**
         * Reads the player data embedded in YouTube's watch page (`"isLiveNow":true`, `"isUpcoming":true`,
         * `"scheduledStartTime":"…"`). A page without player data (consent page, error) is [UNKNOWN] — the video is then
         * treated as an ordinary upload, never as live.
         */
        fun parse(html: String?): WatchPageInfo {
            if (html.isNullOrEmpty() || !html.contains("\"videoDetails\"")) return UNKNOWN
            val liveNow = Regex("\"isLiveNow\"\\s*:\\s*true").containsMatchIn(html)
            val upcoming = Regex("\"isUpcoming\"\\s*:\\s*true").containsMatchIn(html)
            val start = Regex("\"scheduledStartTime\"\\s*:\\s*\"(\\d{9,11})\"").find(html)?.groupValues?.get(1)?.toLongOrNull()?.let { it * SECOND }
            return WatchPageInfo(live = liveNow && !upcoming, upcoming = upcoming && !liveNow, scheduledStart = start, known = true)
        }

        private const val SECOND = 1000L
    }

    val kind: EventKind get() = when {
        live -> EventKind.LIVE
        upcoming -> EventKind.UPCOMING
        else -> EventKind.VIDEO
    }
}

/** Which notifications are on (from AppSettings; Community stays off — no reliable source). */
data class NotifyPrefs(
    val master: Boolean = true,
    val videos: Boolean = true,
    val live: Boolean = true,
    val upcoming: Boolean = true,
    val community: Boolean = false,
    val favoritesOnly: Boolean = false
) {
    fun wants(kind: EventKind): Boolean = master && when (kind) {
        EventKind.VIDEO -> videos
        EventKind.LIVE -> live
        EventKind.UPCOMING -> upcoming
        EventKind.COMMUNITY -> false
    }
}

object NotifyRules {
    /** Uploads older than this are never notified (first check of a channel, a phone that was off). */
    const val MAX_AGE_MS = 2L * 24 * 60 * 60 * 1000

    /** A scheduled stream is checked again from this long before its start until this long after. */
    const val UPCOMING_WINDOW_BEFORE_MS = 10L * 60 * 1000
    const val UPCOMING_WINDOW_AFTER_MS = 12L * 60 * 60 * 1000

    /** At most this many new uploads per run are classified through their watch page (the rest count as videos). */
    const val MAX_CLASSIFY_PER_RUN = 12

    /** At most this many single notifications per run; more are summed up. */
    const val MAX_SINGLE_PER_RUN = 6

    /** A synced channel missing from this many complete syncs in a row counts as unsubscribed. */
    const val MISSED_SYNCS_TO_REMOVE = 2

    val INTERVALS_MIN = listOf(15, 30, 60, 180)
    const val DEFAULT_INTERVAL_MIN = 30

    /**
     * New uploads of a channel: entries not yet known ([known] video ids), not older than [MAX_AGE_MS]. A channel
     * checked for the first time ([firstCheck]) gives none — its current uploads are only remembered.
     */
    fun newUploads(videos: List<FeedVideo>, known: Set<String>, firstCheck: Boolean, now: Long): List<FeedVideo> = if (firstCheck) emptyList() else videos.filter { it.videoId !in known && now - it.published <= MAX_AGE_MS }

    /** Whether a channel's events may be notified (bell, favourites filter). */
    fun channelNotifies(bell: Boolean, favorite: Boolean, prefs: NotifyPrefs): Boolean = bell && (!prefs.favoritesOnly || favorite)

    /** A scheduled stream to look at again now (around its start, not yet reported live). */
    fun recheckUpcoming(scheduledStart: Long?, now: Long): Boolean = scheduledStart != null && now >= scheduledStart - UPCOMING_WINDOW_BEFORE_MS && now <= scheduledStart + UPCOMING_WINDOW_AFTER_MS

    /** Unique key of an event in the history (one notification per video and kind). */
    fun key(videoId: String, kind: EventKind) = "$videoId:${kind.name}"

    /** Missed-sync counter after a sync: reset when seen, +1 when missing from a COMPLETE sync, unchanged otherwise. */
    fun missedAfterSync(before: Int, seen: Boolean, complete: Boolean): Int = when {
        seen -> 0
        complete -> before + 1
        else -> before
    }

    /** A channel's uploads list as a feed (UC… → UU…), the fallback when the channel feed does not answer. */
    fun uploadsFeedOf(channelId: String): String? = if (Regex("UC[A-Za-z0-9_-]{22}").matches(channelId)) "https://www.youtube.com/feeds/videos.xml?playlist_id=UU" + channelId.substring(2) else null

    fun clampInterval(min: Int): Int = INTERVALS_MIN.minByOrNull { kotlin.math.abs(it - min) } ?: DEFAULT_INTERVAL_MIN
}
