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
     * kept from the page and counted; one jump for the whole series, through YouTube's own player, after
     * [SEEK_SERIES_MS] without a further tap.
     */
    const val SEEK_SERIES_MS = 450L
    const val SERIES_GAP_MS = 400L

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
