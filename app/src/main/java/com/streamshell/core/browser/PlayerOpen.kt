package com.streamshell.core.browser

/**
 * How a video is opened in the one player tab, and how the in-page switch is checked afterwards (0.6.0, after the log of
 * 2026-11-01 16:11: a video tapped 1.5 s after the prepared player started loading was switched in a page that was not
 * ready, and never played).
 */
object PlayerOpen {
    /**
     * Double tap in YouTube's fullscreen (0.7.2). Log 2026-11-19: every jump of YouTube's own double tap paused the
     * picture for about half a second and then pulled it back by 0.2–0.6 s (49 of 99 jumps). StreamShell takes the
     * double tap over: the first tap still goes to YouTube (its controls), every further quick tap on the same side is
     * kept from the page and jumps through YouTube's own player.
     *
     * 0.7.3: the jump comes at once (0.7.2 waited [SEEK_SERIES_MS] for the end of the series — it felt late, log
     * 2026-11-22). Taps that come while a jump is still settling ([SEEK_BUSY_MS]) are added up and go as one jump right
     * after it ([SeekBatch]), so quick series still do not pile up jumps.
     */
    const val SEEK_SERIES_MS = 450L
    const val SEEK_BUSY_MS = 350L
    const val SERIES_GAP_MS = 400L

    /** App start (0.7.5): a video opened within this time after the app started is a cold start. */
    const val COLD_START_MS = 6000L

    /** On a cold start with a video, the feed waits at most this long for it (0.7.5). */
    const val FEED_HOLD_MS = 5000L

    fun coldStart(createdAt: Long, now: Long): Boolean = now - createdAt in 0 until COLD_START_MS

    /**
     * Fullscreen brightness / volume swipe after a move of ([dx], [dy]) px (0.7.3): true = an up/down swipe, taken from the
     * page now; false = sideways, the page's; null = not decided yet. [lockPx] is the move needed (~8 dp).
     */
    fun swipeLock(dx: Float, dy: Float, lockPx: Float): Boolean? {
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        return when {
            ax > lockPx && ax > ay -> false
            ay > lockPx && ay > LOCK_RATIO * ax -> true
            else -> null
        }
    }

    private const val LOCK_RATIO = 1.5f

    /** Side of a tap at [x] in [width]: left third = back (false), right third = forward (true), middle = none. */
    fun tapSide(x: Float, width: Float): Boolean? = when {
        width <= 0f -> null
        x < width / 3f -> false
        x > width * 2f / 3f -> true
        else -> null
    }

    /** Fullscreen this soon after a video started may restart YouTube's player (see [restartTarget]). */
    const val FULLSCREEN_RESTART_WINDOW_MS = 6_000L
    const val FULLSCREEN_CHECK_DELAY_MS = 1_500L

    /**
     * Where to go back to when fullscreen restarted the video: [position] is clearly (> 1.5 s) behind the time it has
     * been playing ([expected]); null otherwise.
     */
    fun restartTarget(position: Double, expected: Double): Double? = if (expected > 2.0 && position < expected - 1.5) expected else null

    /** Whether a tap continues a series: quick after the last tap ([lastUp] > 0) and on the same side. */
    fun continuesSeries(lastUp: Long, downAt: Long, lastSide: Boolean?, side: Boolean?): Boolean = side != null && lastSide == side && lastUp > 0 && downAt - lastUp in 0..SERIES_GAP_MS

    /** After the mini player's X, the same video caught again within this time is not reopened (0.7.1). */
    const val CLOSED_GRACE_MS = 3000L

    fun justClosed(videoId: String, closedId: String?, closedAt: Long, now: Long): Boolean = closedId != null && videoId == closedId && now - closedAt in 0..CLOSED_GRACE_MS

    /** Start-up time "tap to playing" counts only when the tap was this recent (0.7.1: one was 319604 ms). */
    const val STARTUP_MAX_MS = 15_000L

    fun startupMs(openedAt: Long, playingAt: Long): Long? = (playingAt - openedAt).takeIf { it in 0..STARTUP_MAX_MS }

    enum class Mode {
        /** Same page, new address (history + popstate): no new page load. */
        IN_PAGE,

        /** The player page is still loading (e.g. the prepared player): the video address is loaded directly. */
        FULL_LOAD_STILL_LOADING,

        /** No usable player page yet: the video address is loaded. */
        FULL_LOAD
    }

    fun mode(hasPlayerPage: Boolean, pageLoading: Boolean): Mode = when {
        !hasPlayerPage -> Mode.FULL_LOAD
        pageLoading -> Mode.FULL_LOAD_STILL_LOADING
        else -> Mode.IN_PAGE
    }

    /** Page script: "videoId|playerState" of YouTube's player ("" / -9 when unknown). */
    const val CHECK = "(function(){try{var mp=document.getElementById('movie_player');if(!mp||!mp.getVideoData)return '|-9';" +
        "var id=(mp.getVideoData()||{}).video_id||'';var s=mp.getPlayerState?mp.getPlayerState():-9;return id+'|'+s;}catch(e){return '|-9';}})()"

    data class Check(val videoId: String, val state: Int)

    fun parse(raw: String?): Check {
        val s = raw?.trim()?.trim('"').orEmpty()
        val i = s.lastIndexOf('|')
        if (i < 0) return Check("", UNKNOWN)
        return Check(s.substring(0, i), s.substring(i + 1).toIntOrNull() ?: UNKNOWN)
    }

    /**
     * True when the in-page switch worked: the player carries the new video and, with autoplay on, has started
     * (playing 1, paused 2 by the user or a script, buffering 3; an ad also shows as one of these). Not started (-1) or
     * only cued (5) with autoplay on means the page did not take the switch → load the address.
     */
    fun switched(check: Check, target: String, autoplay: Boolean): Boolean {
        val started = check.state !in setOf(UNSTARTED, CUED, UNKNOWN)
        return check.videoId == target && (!autoplay || started)
    }

    fun stateName(state: Int): String = when (state) {
        UNSTARTED -> "not started"
        0 -> "ended"
        1 -> "playing"
        2 -> "paused"
        3 -> "buffering"
        CUED -> "cued"
        else -> "unknown"
    }

    const val UNSTARTED = -1
    const val CUED = 5
    const val UNKNOWN = -9
}

/** Adds up fullscreen double-tap jumps (0.7.3): one jump at once, taps during [PlayerOpen.SEEK_BUSY_MS] after it as one more. Pure. */
class SeekBatch(private val busyMs: Long = PlayerOpen.SEEK_BUSY_MS) {
    private var pending = 0
    private var busyUntil = 0L

    /** A tap of [seconds] at [now]: the jump to make now, or null (added to the next one, see [due]). */
    fun add(seconds: Int, now: Long): Int? {
        pending += seconds
        return if (now >= busyUntil) take(now) else null
    }

    /** The added-up jump once the last one has settled, or null. */
    fun due(now: Long): Int? = if (pending != 0 && now >= busyUntil) take(now) else null

    /** Time when [due] can give the waiting jump. */
    fun readyAt(): Long = busyUntil

    val waiting: Boolean get() = pending != 0

    private fun take(now: Long): Int? {
        val s = pending
        pending = 0
        if (s == 0) return null
        busyUntil = now + busyMs
        return s
    }
}
