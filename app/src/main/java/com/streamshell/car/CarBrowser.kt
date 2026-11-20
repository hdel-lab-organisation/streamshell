package com.streamshell.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.streamshell.R
import com.streamshell.core.browser.BrowserEngine
import com.streamshell.core.browser.PageKind
import com.streamshell.core.browser.SiteApp
import com.streamshell.core.media.MediaState

/**
 * StreamShell on the car screen. Android Auto gives a navigation app a surface to draw on; a private virtual display
 * is put on that surface and a [Presentation] on it holds the browser (the same engine and YouTube scripts as on the
 * phone). Taps and drags from the car screen are passed on as touches.
 *
 * Layout (0.7.0, option C "adaptive navigation"): the page fills the screen edge to edge under a slim header in
 * Android Auto's top band (source and page title; the band's right part is Android Auto's buttons). The navigation
 * adapts: a rail at one side on wide screens (≥ 1.6:1, e.g. the DS7), a bar at the bottom on narrower or portrait
 * screens, one menu button on very small ones ([CarDisplay.navPlacement]); its buttons follow the page
 * ([CarDisplay.navItems]). No play bar: YouTube's own player has the controls. Colours from [CarTheme], views from
 * [CarViews]. The virtual display's density is chosen by [CarDisplay.optimalDpi].
 *
 * While [gate] does not allow video, the page is covered (the sound keeps playing) and taps on it are ignored; the
 * navigation (Home, sources) and the cover's previous · play · next stay usable. ParkGate itself is unchanged.
 */
class CarBrowser(
    private val context: Context,
    private val engine: BrowserEngine,
    private val gate: ParkGate,
    /** Diagnostics line (only written while Diagnostics is on). */
    private val log: (String) -> Unit = {}
) : SurfaceCallback {
    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private var pageFrame: FrameLayout? = null
    private var cover: View? = null
    private var coverText: TextView? = null
    private var nowTitle: TextView? = null
    private var nowArtist: TextView? = null
    private var coverControls: View? = null
    private var coverPlay: ImageButton? = null
    private var navBox: LinearLayout? = null
    private var menuButton: View? = null
    private var placement = CarDisplay.NavPlacement.SIDE
    private var navKey: Pair<Boolean, Boolean>? = null
    private var headerView: View? = null
    private var headerSource: TextView? = null
    private var headerTitle: TextView? = null
    private var railYouTube: ImageButton? = null
    private var railMusic: ImageButton? = null
    private var views: CarViews? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private var playing = true
    private var now = CarNowPlaying()

    /** What the car shows: YouTube or YouTube Music (rail; first one from the setting "Android Auto: opens with"). */
    @Volatile var source: SiteApp = SiteApp.YOUTUBE

    /** Rail at the left edge (setting "Android Auto: controls on the left"); read when the surface is created. */
    @Volatile var railLeft = true

    /** Called by the rail's Search button (the screen opens the car keyboard). */
    var onSearchRequested: () -> Unit = {}

    /** Chosen density and page size, for the log / About. */
    var describe: String = ""
        private set

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        val dm = context.getSystemService(DisplayManager::class.java) ?: return
        val dpi = CarDisplay.optimalDpi(container.width, container.height, container.dpi)
        val widthDp = CarDisplay.dp(container.width, dpi)
        describe = "car screen ${container.width}×${container.height} px, car dpi ${container.dpi} → $dpi " +
            "($widthDp×${CarDisplay.dp(container.height, dpi)} dp), page edge to edge"
        val vd = dm.createVirtualDisplay(
            "StreamShell car",
            container.width,
            container.height,
            dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        ) ?: run {
            log("surface: virtual display could not be created")
            return
        }
        log("surface available: $describe")
        surfaceW = container.width
        surfaceH = container.height
        display = vd
        // Dark theme: the page is told the screen prefers dark colours (YouTube follows it).
        val p = Presentation(context, vd.display, android.R.style.Theme_DeviceDefault_NoActionBar)
        val v = CarViews(p.context)
        views = v
        placement = CarDisplay.navPlacement(widthDp, CarDisplay.dp(container.height, dpi))
        describe += ", navigation ${placement.name.lowercase()}"
        val box = FrameLayout(p.context).apply { setBackgroundColor(CarTheme.BACKGROUND) }
        val head = header(v)
        // The page alone, edge to edge — no card, no gaps, no side panel (user, 2026-11-03).
        val frame = pageFrame(v)
        val nav = LinearLayout(p.context).apply {
            orientation = if (placement == CarDisplay.NavPlacement.BOTTOM) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(CarTheme.BACKGROUND_2)
            val pad = v.px(6)
            setPadding(pad, pad, pad, pad)
        }
        navBox = nav
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val column = LinearLayout(p.context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(head, LinearLayout.LayoutParams(match, 0))
        column.addView(frame, LinearLayout.LayoutParams(match, 0, 1f))
        when (placement) {
            CarDisplay.NavPlacement.SIDE -> {
                val row = LinearLayout(p.context).apply { orientation = LinearLayout.HORIZONTAL }
                val railParams = LinearLayout.LayoutParams(v.px(CarDisplay.RAIL_DP), match)
                val colParams = LinearLayout.LayoutParams(0, match, 1f)
                if (railLeft) {
                    row.addView(nav, railParams)
                    row.addView(column, colParams)
                } else {
                    row.addView(column, colParams)
                    row.addView(nav, railParams)
                }
                box.addView(row, FrameLayout.LayoutParams(match, match))
            }

            CarDisplay.NavPlacement.BOTTOM -> {
                column.addView(nav, LinearLayout.LayoutParams(match, v.px(CarDisplay.BAR_DP)))
                box.addView(column, FrameLayout.LayoutParams(match, match))
            }

            CarDisplay.NavPlacement.MENU -> {
                box.addView(column, FrameLayout.LayoutParams(match, match))
                // One menu button over the page's corner; it opens the navigation as a panel over the page.
                nav.background = v.glass(CarTheme.GLASS)
                nav.visibility = View.GONE
                val side = if (railLeft) Gravity.START else Gravity.END
                box.addView(nav, FrameLayout.LayoutParams(v.px(CarDisplay.RAIL_DP), match, side or Gravity.BOTTOM))
                val m = v.iconButton(R.drawable.ic_car_menu, "Menu", MENU_PAD) { nav.visibility = if (nav.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
                m.background = v.glass(CarTheme.ELEVATED, radiusDp = CarTheme.RADIUS_CONTROL)
                val size = v.px(CarTheme.CONTROL_BUTTON)
                box.addView(m, FrameLayout.LayoutParams(size, size, side or Gravity.BOTTOM).apply { setMargins(v.px(CarTheme.GAP), 0, v.px(CarTheme.GAP), v.px(CarTheme.GAP)) })
                menuButton = m
            }
        }
        p.setContentView(box)
        runCatching { p.show() }.onFailure { log("surface: presentation could not be shown (${it.javaClass.simpleName})") }
        presentation = p
        root = box
        headerView = head
        pageFrame = frame
        navKey = null
        updateNav()
        engine.setKeepVisible(PAGE_ID)
        refresh()
        if (startWanted) start()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /**
     * Android Auto keeps a band at the top for its own buttons (top right). That band is StreamShell's header (left part);
     * the rail runs the full height; only the other edges are padded (log of 2026-11-02: 132 px of 600).
     */
    override fun onVisibleAreaChanged(visibleArea: Rect) {
        val r = root ?: return
        log("visible area ${visibleArea.left},${visibleArea.top}–${visibleArea.right},${visibleArea.bottom}")
        main.post {
            val pad = CarDisplay.insets(surfaceW, surfaceH, visibleArea.left, visibleArea.top, visibleArea.right, visibleArea.bottom)
            // Edge to edge: Android Auto's thin frame (18 px on the DS7) is not kept free, only real panels.
            val edge = CarDisplay.edgeToEdge(pad, (CarDisplay.FRAME_DP * r.resources.displayMetrics.density).toInt())
            r.setPadding(edge.left, 0, edge.right, edge.bottom)
            headerView?.let { h ->
                h.layoutParams = h.layoutParams.apply { height = pad.top }
                h.visibility = if (pad.top > 0) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        log("stable area ${stableArea.left},${stableArea.top}–${stableArea.right},${stableArea.bottom}")
    }

    /** Header in Android Auto's top band: wordmark, source tag, page title (left part; the right is Android Auto's). */
    private fun header(v: CarViews): View = LinearLayout(v.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(v.px(4), 0, 0, 0)
        visibility = View.GONE
        addView(v.wordmark(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val tag = v.chip("YouTube", CarTheme.TEXT_2)
        addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = v.px(CarTheme.GAP) })
        val title = v.text(CarTheme.BODY_SP, CarTheme.TEXT_2).apply { setPadding(v.px(CarTheme.GAP), 0, v.px(8), 0) }
        addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The right part of the band belongs to Android Auto's buttons.
        addView(View(v.context), LinearLayout.LayoutParams(0, 1, CarDisplay.HEADER_FREE_WEIGHT))
        headerSource = tag
        headerTitle = title
    }

    /**
     * Fills the navigation with the buttons for the current page ([CarDisplay.navItems]); rebuilt only when the page
     * kind or the cover changes.
     */
    private fun updateNav() {
        val nav = navBox ?: return
        val v = views ?: return
        val video = PageKind.isVideoPage(engine.pages.value[PAGE_ID]?.url)
        val covered = !gate.videoAllowed
        val key = video to covered
        if (key == navKey) return
        navKey = key
        nav.removeAllViews()
        railYouTube = null
        railMusic = null
        val horizontal = nav.orientation == LinearLayout.HORIZONTAL
        CarDisplay.navItems(video, covered).forEach { item ->
            val (icon, label) = iconOf(item)
            val b = v.iconButton(icon, label, RAIL_PAD) {
                if (placement == CarDisplay.NavPlacement.MENU) nav.visibility = View.GONE
                perform(item)
            }
            v.setActive(b, false)
            when (item) {
                CarDisplay.NavItem.YOUTUBE -> railYouTube = b
                CarDisplay.NavItem.MUSIC -> railMusic = b
                else -> Unit
            }
            val lp = if (horizontal) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(v.px(2), 0, v.px(2), 0) }
            } else if (placement == CarDisplay.NavPlacement.MENU) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, v.px(CarTheme.CONTROL_BUTTON)).apply { setMargins(0, v.px(2), 0, v.px(2)) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, v.px(2), 0, v.px(2)) }
            }
            nav.addView(b, lp)
        }
        showSource()
        log("navigation (${placement.name.lowercase()}): " + CarDisplay.navItems(video, covered).joinToString(", ") { it.name.lowercase() })
    }

    private fun iconOf(item: CarDisplay.NavItem): Pair<Int, String> = when (item) {
        CarDisplay.NavItem.HOME -> R.drawable.ic_car_home to "Home"
        CarDisplay.NavItem.SEARCH -> R.drawable.ic_car_search to "Search"
        CarDisplay.NavItem.BACK -> R.drawable.ic_car_back to "Back"
        CarDisplay.NavItem.FORWARD -> R.drawable.ic_car_forward to "Forward"
        CarDisplay.NavItem.YOUTUBE -> R.drawable.ic_car_youtube to "YouTube"
        CarDisplay.NavItem.MUSIC -> R.drawable.ic_car_music to "YouTube Music"
        CarDisplay.NavItem.LIBRARY -> R.drawable.ic_car_library to "Library"
        CarDisplay.NavItem.SCROLL_UP -> R.drawable.ic_car_up to "Scroll up"
        CarDisplay.NavItem.SCROLL_DOWN -> R.drawable.ic_car_down to "Scroll down"
    }

    private fun perform(item: CarDisplay.NavItem) = when (item) {
        CarDisplay.NavItem.HOME -> home()
        CarDisplay.NavItem.SEARCH -> onSearchRequested()
        CarDisplay.NavItem.BACK -> back()
        CarDisplay.NavItem.FORWARD -> forward()
        CarDisplay.NavItem.YOUTUBE -> useSource(SiteApp.YOUTUBE)
        CarDisplay.NavItem.MUSIC -> useSource(SiteApp.YOUTUBE_MUSIC)
        CarDisplay.NavItem.LIBRARY -> library()
        CarDisplay.NavItem.SCROLL_UP -> scroll(-1)
        CarDisplay.NavItem.SCROLL_DOWN -> scroll(1)
    }

    /** The page, filling everything beside the navigation and under the header, with the cover for driving on top. */
    private fun pageFrame(v: CarViews): FrameLayout = FrameLayout(v.context).apply {
        setBackgroundColor(CarTheme.BACKGROUND)
        addView(engine.attach(PAGE_ID, v.context, createInContext = true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(coverView(v), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showSource() {
        val v = views ?: return
        railYouTube?.let { v.setActive(it, source == SiteApp.YOUTUBE) }
        railMusic?.let { v.setActive(it, source == SiteApp.YOUTUBE_MUSIC, CarTheme.MUSIC_RED) }
        headerSource?.apply {
            text = source.title
            setTextColor(if (source == SiteApp.YOUTUBE_MUSIC) CarTheme.MUSIC_RED else CarTheme.TEXT_2)
        }
    }

    /**
     * The surface (picture) ends — Android Auto closed the screen or switched app. Only the presentation goes; the page
     * and its sound belong to the car session (engine) and keep running until the session ends.
     */
    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        log("surface destroyed (picture gone, page and sound stay with the car session)")
        release()
    }

    private var coverShown: Boolean? = null
    private var ignoredTapLogged = false

    /**
     * The cover while the picture may not show: a short message and what plays (text only). Its own previous ·
     * play/pause · next.
     */
    private fun coverView(v: CarViews): View = LinearLayout(v.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(CarTheme.BACKGROUND)
        val p = v.px(28)
        setPadding(p, p, p, p)
        val msg = v.text(CarTheme.BODY_SP, CarTheme.MUTED, lines = 2).apply { gravity = Gravity.CENTER }
        val title = v.text(COVER_TITLE_SP, CarTheme.TEXT, bold = true, lines = 2).apply { gravity = Gravity.CENTER }
        val artist = v.text(CarTheme.TITLE_SP - 2f, CarTheme.TEXT_2).apply { gravity = Gravity.CENTER }
        val row = LinearLayout(v.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val side = LinearLayout.LayoutParams(v.px(CarTheme.CONTROL_BUTTON + 12), v.px(CarTheme.CONTROL_BUTTON + 12))
        row.addView(v.iconButton(R.drawable.ic_car_prev, "Previous", COVER_PAD) { previous() }, side)
        val play = v.playButton { togglePlay() }
        row.addView(play, LinearLayout.LayoutParams(v.px(CarTheme.PLAY_BUTTON + 12), v.px(CarTheme.PLAY_BUTTON + 12)).apply { setMargins(v.px(16), 0, v.px(16), 0) })
        row.addView(v.iconButton(R.drawable.ic_car_next, "Next", COVER_PAD) { next() }, LinearLayout.LayoutParams(side))
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        addView(msg, LinearLayout.LayoutParams(match, wrap))
        addView(title, LinearLayout.LayoutParams(match, wrap).apply { topMargin = v.px(18) })
        addView(artist, LinearLayout.LayoutParams(match, wrap).apply { topMargin = v.px(6) })
        addView(row, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = v.px(22) })
        cover = this
        coverText = msg
        nowTitle = title
        nowArtist = artist
        coverControls = row
        coverPlay = play
    }

    /** Shows or hides the cover after the gate changed. */
    fun refresh() {
        val c = cover ?: return
        val msg = coverText ?: return
        main.post {
            val show = !gate.videoAllowed
            if (coverShown != show) {
                coverShown = show
                ignoredTapLogged = false
                log(if (show) "cover on: picture hidden, page taps ignored; sound, navigation and cover controls work" else "cover off: picture shown, page taps on")
            }
            c.visibility = if (show) View.VISIBLE else View.GONE
            updateNav()
            msg.text = if (gate.needsConfirmation) {
                "The picture shows only while the car is parked. Tap \"Parked\" when you have stopped."
            } else {
                "Sound only while driving — the picture comes back when the car stands still."
            }
        }
    }

    // ---------------------------------------------------------------- controls

    /**
     * Every second while the car screen shows: what plays (the page's media session, else the page title), position,
     * play state and the video's picture; the car style for YouTube's page. Nothing is shown that the page does not say.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (root == null) return
            val page = engine.pages.value[PAGE_ID]
            val url = page?.url
            val video = PageKind.isVideoPage(url)
            headerTitle?.text = page?.title?.removeSuffix(" - YouTube")?.removeSuffix(" - YouTube Music").orEmpty()
            if (com.streamshell.core.browser.YouTubeSite.isYouTube(com.streamshell.core.browser.UrlHosts.hostOf(url))) engine.run(PAGE_ID, CarDisplay.PAGE_STYLE)
            updateNav()
            val music = com.streamshell.core.browser.MusicSite.isMusic(com.streamshell.core.browser.UrlHosts.hostOf(url))
            val id = com.streamshell.core.sponsor.SponsorBlockApi.videoIdOf(url)
            engine.nowPlaying(PAGE_ID) { np ->
                now = now.copy(
                    title = np?.first ?: if (video) headerTitle?.text?.toString().orEmpty() else "",
                    artist = np?.second.orEmpty(),
                    videoId = id,
                    music = music
                )
                render()
            }
            engine.probeMedia(PAGE_ID) { st ->
                now = now.copy(playing = st == MediaState.PLAYING)
                showPlaying(now.playing)
                render()
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun render() {
        nowTitle?.text = now.title.ifEmpty { "Nothing playing" }
        nowArtist?.text = now.artist
    }

    private fun showPlaying(now: Boolean) {
        playing = now
        listOfNotNull(coverPlay).forEach { b ->
            b.setImageResource(if (now) R.drawable.ic_notif_pause else R.drawable.ic_notif_play)
            b.contentDescription = if (now) "Pause" else "Play"
        }
    }

    // ---------------------------------------------------------------- input from the car screen

    override fun onClick(x: Float, y: Float) {
        val r = root ?: return
        main.post {
            // While the picture is covered, taps on the page are ignored; navigation and the cover's controls still work.
            val onNav = navBox?.takeIf { it.visibility == View.VISIBLE }?.let { inside(it, r, x, y) } == true || inside(menuButton, r, x, y)
            if (!gate.videoAllowed && inside(pageFrame, r, x, y) && !inside(coverControls, r, x, y) && !onNav) {
                if (!ignoredTapLogged) log("tap on the covered page ignored (further ones not logged until the cover changes)")
                ignoredTapLogged = true
                return@post
            }
            val t = SystemClock.uptimeMillis()
            send(r, t, t, MotionEvent.ACTION_DOWN, x, y)
            send(r, t, t + 60, MotionEvent.ACTION_UP, x, y)
        }
    }

    private var dragDown = 0L
    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false
    private val endDrag = Runnable {
        root?.let { send(it, dragDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, dragX, dragY) }
        dragging = false
    }

    /** Pan gestures (in pan mode) become a finger drag on the page: scrolling the feed, the chat, the description. */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        val r = root
        val f = pageFrame
        if (!gate.videoAllowed || r == null || f == null) return
        main.post {
            val now = SystemClock.uptimeMillis()
            val area = boundsIn(f, r)
            if (!dragging) {
                dragging = true
                dragDown = now
                dragX = area.exactCenterX()
                dragY = area.exactCenterY()
                send(r, dragDown, now, MotionEvent.ACTION_DOWN, dragX, dragY)
            }
            dragX = (dragX - distanceX).coerceIn(area.left + 1f, area.right - 1f)
            dragY = (dragY - distanceY).coerceIn(area.top + 1f, area.bottom - 1f)
            send(r, dragDown, now, MotionEvent.ACTION_MOVE, dragX, dragY)
            main.removeCallbacks(endDrag)
            main.postDelayed(endDrag, 120)
        }
    }

    private fun send(target: View, down: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        runCatching { target.dispatchTouchEvent(e) }
        e.recycle()
    }

    /** Bounds of [v] in [root]'s coordinates. */
    private fun boundsIn(v: View, root: View): Rect {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        root.getLocationInWindow(b)
        val l = a[0] - b[0]
        val t = a[1] - b[1]
        return Rect(l, t, l + v.width, t + v.height)
    }

    private fun inside(v: View?, root: View, x: Float, y: Float): Boolean = v != null && boundsIn(v, root).contains(x.toInt(), y.toInt())

    // ---------------------------------------------------------------- actions

    fun home() = engine.load(PAGE_ID, homeOf(source))

    fun next() = engine.mediaAction(PAGE_ID, com.streamshell.core.browser.MediaSessionScripts.NEXT) { how -> log("next track ($how)") }
    fun previous() = engine.mediaAction(PAGE_ID, com.streamshell.core.browser.MediaSessionScripts.PREVIOUS) { how -> log("previous track ($how)") }

    /** YouTube or YouTube Music: its home page opens in the car's page (one page, one sound). */
    fun useSource(s: SiteApp) {
        if (s == source) {
            home()
            return
        }
        source = s
        log("source: ${source.title}")
        showSource()
        home()
    }

    /** The source's library (YouTube: You / library; YouTube Music: Library). */
    fun library() {
        val dest = source.destinations.firstOrNull { it.id == "library" || it.id == "you" } ?: return
        engine.load(PAGE_ID, dest.url)
    }

    fun back() = engine.goBack(PAGE_ID)
    fun forward() = engine.goForward(PAGE_ID)
    fun scroll(direction: Int) {
        if (gate.videoAllowed) engine.run(PAGE_ID, CarDisplay.scrollScript(direction))
    }
    fun search(query: String) {
        if (query.isNotBlank()) engine.load(PAGE_ID, source.searchUrlFor(query))
    }

    /** Pauses or plays depending on what the page really does (the video may have been paused on the page itself). */
    fun togglePlay(onDone: () -> Unit = {}) {
        engine.probeMedia(PAGE_ID) { st ->
            val play = st != MediaState.PLAYING
            if (play) engine.playMedia(PAGE_ID) else engine.pauseMedia(PAGE_ID)
            main.post { showPlaying(play) }
            onDone()
        }
    }

    val isPlaying get() = playing

    private var started = false
    private var startWanted = false

    /**
     * Opens the source's home page — but only once the car screen's surface exists: the page's WebView must be created
     * with the car screen's context to get the car screen's density (see [BrowserEngine.attach]).
     */
    fun start() {
        startWanted = true
        if (!started && presentation != null) {
            started = true
            engine.load(PAGE_ID, homeOf(source))
            log("page created for the car screen (${presentation?.context?.resources?.displayMetrics?.densityDpi} dpi)")
        }
    }

    /** Ends the picture only (presentation and virtual display); the engine and its sound are not touched. */
    fun release() {
        coverShown = null
        main.removeCallbacks(endDrag)
        main.removeCallbacks(tick)
        runCatching { engine.detach(PAGE_ID) }
        runCatching { presentation?.dismiss() }
        runCatching { display?.release() }
        presentation = null
        display = null
        root = null
        pageFrame = null
        cover = null
        coverText = null
        nowTitle = null
        nowArtist = null
        coverControls = null
        coverPlay = null
        headerView = null
        headerSource = null
        headerTitle = null
        railYouTube = null
        railMusic = null
        views = null
        navBox = null
        menuButton = null
        navKey = null
    }

    companion object {
        const val PAGE_ID = 1L
        const val HOME = "https://m.youtube.com/"
        const val MUSIC_HOME = "https://music.youtube.com/"

        fun homeOf(source: SiteApp) = if (source == SiteApp.YOUTUBE_MUSIC) MUSIC_HOME else HOME
        private const val TICK_MS = 1000L
        private const val RAIL_PAD = 14
        private const val MENU_PAD = 16
        private const val COVER_PAD = 18
        private const val COVER_TITLE_SP = 24f
    }
}

/** What plays in the car's page (built from the page's own state every second; nothing is invented). */
data class CarNowPlaying(
    val title: String = "",
    val artist: String = "",
    val videoId: String? = null,
    val position: Double = Double.NaN,
    val duration: Double = Double.NaN,
    val playing: Boolean = false,
    val music: Boolean = false
) {
    val hasMedia get() = title.isNotEmpty() || videoId != null
}
