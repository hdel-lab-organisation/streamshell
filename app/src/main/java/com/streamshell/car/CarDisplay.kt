package com.streamshell.car

/**
 * Size rules for the car screen (pure, unit-tested). Android Auto reports a density for its surface that does not suit
 * a web page on wide head units (DS7 Crossback, 0.5.6 photos: the page was far too large). StreamShell picks its own
 * density so the page gets about [TARGET_HEIGHT_DP] of height — like a phone held sideways — but never less than
 * [MIN_WIDTH_DP] of width, within [MIN_DPI]..[MAX_DPI].
 */
object CarDisplay {
    const val TARGET_HEIGHT_DP = 480
    const val MIN_WIDTH_DP = 640
    const val MIN_DPI = 120
    const val MAX_DPI = 320

    /** Navigation rail width / bar height (dp). */
    const val RAIL_DP = 72
    const val BAR_DP = 72

    fun optimalDpi(widthPx: Int, heightPx: Int, hostDpi: Int): Int {
        if (widthPx <= 0 || heightPx <= 0) return hostDpi.coerceIn(MIN_DPI, MAX_DPI)
        val forHeight = heightPx * BASE / TARGET_HEIGHT_DP
        val widthLimit = widthPx * BASE / MIN_WIDTH_DP
        return minOf(forHeight, widthLimit).coerceIn(MIN_DPI, MAX_DPI)
    }

    /** Padding inside the icon buttons (dp): with ~80 dp rows the icons come out at about 32 dp. */
    const val ICON_PAD_DP = 22

    /** Share of the header left free for Android Auto's own buttons (weight against the title's 1). */
    const val HEADER_FREE_WEIGHT = 1.2f

    /** Edges kept free of StreamShell's content, from Android Auto's visible area (top = height of the header band). */
    data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun insets(width: Int, height: Int, visLeft: Int, visTop: Int, visRight: Int, visBottom: Int): Insets {
        if (width <= 0 || height <= 0 || visRight <= visLeft || visBottom <= visTop) return Insets(0, 0, 0, 0)
        // Each side at most half the screen (a host panel over one side must not squeeze the page to nothing).
        return Insets(
            left = visLeft.coerceIn(0, width / 2),
            top = visTop.coerceIn(0, height / 2),
            right = (width - visRight).coerceIn(0, width / 2),
            bottom = (height - visBottom).coerceIn(0, height / 2)
        )
    }

    /**
     * Car-only style for YouTube's mobile page: the feed as a grid of tiles about 220 px wide (as many per row as fit) (one huge tile filled the car
     * screen, photo of 2026-11-02), Shorts shelves across the grid, the video page's player no higher than the screen.
     * Installed once per page; harmless where YouTube's markup differs (then nothing changes).
     */
    const val PAGE_STYLE = "(function(){if(document.getElementById('ss-car-style'))return 'ok';var s=document.createElement('style');s.id='ss-car-style';" +
        "s.textContent='.rich-grid-renderer-contents{display:grid!important;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));column-gap:16px;row-gap:8px;padding:0 12px!important}" +
        ".rich-grid-renderer-contents>ytm-rich-item-renderer{width:auto!important;max-width:none!important;margin:0!important}" +
        ".rich-grid-renderer-contents>ytm-rich-section-renderer{grid-column:1/-1}" +
        "ytm-watch .player-container,ytm-watch #player-container-id{max-height:70vh!important}" +
        "ytm-watch .player-size,ytm-watch video{max-height:70vh!important}';" +
        "(document.head||document.documentElement).appendChild(s);return 'ok';})()"

    /** Android Auto's thin frame around the visible area is drawn over (edge to edge); wider insets are host panels. */
    const val FRAME_DP = 24

    /** [i] with every side thinner than [thresholdPx] set to 0 (top unchanged: it is the header band). */
    fun edgeToEdge(i: Insets, thresholdPx: Int): Insets = Insets(
        left = if (i.left < thresholdPx) 0 else i.left,
        top = i.top,
        right = if (i.right < thresholdPx) 0 else i.right,
        bottom = if (i.bottom < thresholdPx) 0 else i.bottom
    )

    /** Size in dp of [px] pixels at [dpi]. */
    fun dp(px: Int, dpi: Int): Int = if (dpi <= 0) px else px * BASE / dpi

    /** "1:05", "12:00", "1:02:03"; "" for unknown. */
    fun clock(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return ""
        val s = seconds.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Progress 0..1000 of [position] in [duration]; 0 when unknown. */
    fun progress(position: Double, duration: Double): Int {
        val known = position.isFinite() && duration.isFinite() && duration > 0
        return if (known) (position / duration * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX) else 0
    }

    /** Page script: scrolls the page by most of a screen, up (-1) or down (1). */
    fun scrollScript(direction: Int): String {
        val sign = if (direction < 0) -1 else 1
        return "(function(){window.scrollBy({top:$sign*Math.round(window.innerHeight*0.7),behavior:'smooth'});})()"
    }

    const val PROGRESS_MAX = 1000
    private const val BASE = 160

    // ------------------------------------------------------------ adaptive navigation (0.7.0, option C)

    /** Where the navigation goes: a rail at the side, a bar at the bottom, or a single menu button. */
    enum class NavPlacement { SIDE, BOTTOM, MENU }

    /** From this aspect ratio (×10) on, the screen is wide enough for a side rail (DS7: 960×480 dp = 2:1). */
    const val SIDE_MIN_RATIO_X10 = 16

    /** Below this size (dp) the navigation shrinks to one menu button so that the page keeps its room. */
    const val MENU_MAX_WIDTH_DP = 520
    const val MENU_MAX_HEIGHT_DP = 320

    /**
     * Placement for a screen of [widthDp]×[heightDp] (at the density StreamShell chose): very small → MENU; at least
     * 1.6:1 → SIDE; narrower or portrait → BOTTOM.
     */
    fun navPlacement(widthDp: Int, heightDp: Int): NavPlacement = when {
        widthDp <= 0 || heightDp <= 0 -> NavPlacement.SIDE
        widthDp < MENU_MAX_WIDTH_DP || heightDp < MENU_MAX_HEIGHT_DP -> NavPlacement.MENU
        widthDp * 10 >= heightDp * SIDE_MIN_RATIO_X10 -> NavPlacement.SIDE
        else -> NavPlacement.BOTTOM
    }

    enum class NavItem { HOME, SEARCH, BACK, FORWARD, YOUTUBE, MUSIC, LIBRARY, SCROLL_UP, SCROLL_DOWN }

    /**
     * The navigation's buttons for what is on screen. Feed and other pages: Home, Search, Back, the two sources,
     * Library, scroll. A video page: Back, Home, Forward and the sources — no play controls (YouTube's own player has
     * them). While the picture is covered (driving): Home and the sources only; the cover has previous · play · next.
     */
    fun navItems(videoPage: Boolean, covered: Boolean): List<NavItem> = when {
        covered -> listOf(NavItem.HOME, NavItem.YOUTUBE, NavItem.MUSIC)
        videoPage -> listOf(NavItem.BACK, NavItem.HOME, NavItem.FORWARD, NavItem.YOUTUBE, NavItem.MUSIC)
        else -> listOf(NavItem.HOME, NavItem.SEARCH, NavItem.BACK, NavItem.YOUTUBE, NavItem.MUSIC, NavItem.LIBRARY, NavItem.SCROLL_UP, NavItem.SCROLL_DOWN)
    }
}
