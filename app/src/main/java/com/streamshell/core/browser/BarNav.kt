package com.streamshell.core.browser

/**
 * The site's bottom bar on YouTube (0.7.4, log 2026-11-25): how a tap on a section (Home, Subscriptions, …) gets
 * there. Before, every tap loaded the whole page again — white for 0.5–1.4 s, and in the player tab the video was
 * gone and that tab became a second home. Pure, unit-tested.
 */
object BarNav {
    enum class How {
        /** Already on that section: scroll to the top (like the YouTube app). */
        TOP,

        /** YouTube's page changes inside the loaded page (history + popstate, as its own links). */
        IN_PAGE,

        /** A normal page load (another site, Shorts, nothing loaded yet). */
        LOAD
    }

    /** A second tap on the same section within this time is ignored (two loads in 0.6 s in the log). */
    const val REPEAT_MS = 1500L

    /** After this the page must have left its old place, else the section is loaded normally. */
    const val CHECK_MS = 2500L

    fun how(current: String?, target: String): How {
        val c = parse(current) ?: return How.LOAD
        val t = parse(target) ?: return How.LOAD
        if (c.host != t.host || !YouTubeSite.isYouTube(t.host) || c.host != "m.youtube.com") return How.LOAD
        // Shorts has its own player page: leaving or entering it in place is not reliable.
        if (t.path.startsWith("/shorts") || c.path.startsWith("/shorts")) return How.LOAD
        return if (c.path == t.path && c.query == t.query) How.TOP else How.IN_PAGE
    }

    fun repeated(lastUrl: String?, lastAt: Long, url: String, now: Long): Boolean = lastUrl == url && now - lastAt in 0 until REPEAT_MS

    /** "/feed/subscriptions" (path and query) of [url], or null. */
    fun pathOf(url: String?): String? = parse(url)?.let { it.path + (it.query?.let { q -> "?$q" } ?: "") }

    /** The page is still where it was ([before]) and the target is elsewhere: YouTube did not follow, load it. */
    fun needsLoad(where: String?, before: String?, target: String): Boolean {
        val t = pathOf(target) ?: return false
        return where != null && before != null && where == before && where != t
    }

    fun switchScript(url: String): String {
        val q = org.json.JSONObject.quote(url)
        return "(function(u){try{var t=new URL(u);history.pushState({},'',t.pathname+t.search);" +
            "window.dispatchEvent(new PopStateEvent('popstate',{state:{}}));return 'ok';}catch(e){return 'no';}})($q)"
    }

    const val CHECK = "(function(){return location.pathname+location.search;})()"

    fun parseCheck(raw: String?): String? = raw?.trim()?.removeSurrounding("\"")?.takeIf { it.startsWith("/") }

    private data class Parts(val host: String, val path: String, val query: String?)

    private fun parse(url: String?): Parts? {
        val u = url?.let { runCatching { java.net.URI(it) }.getOrNull() }
        val host = u?.host
        if (u == null || host == null || u.scheme != "https") return null
        val path = u.rawPath.orEmpty().ifEmpty { "/" }.let { if (it.length > 1) it.trimEnd('/') else it }
        return Parts(host, path, u.rawQuery?.takeIf { it.isNotEmpty() })
    }
}

/**
 * YouTube player tabs (0.7.4): a link to a page that is not a video — channel, search, list, feed, hashtag — goes to
 * the site's instance tab, and the video goes to the mini player (like the YouTube app). Before, the channel opened in
 * the player tab itself; it stayed empty until a pull to refresh (log 2026-11-25 16:45:04–09), and Home there made the
 * player tab a second home.
 */
object YouTubePlayerLinks {
    const val CHANNEL = "streamShellPage"
    const val FLAG = "window.__ssPlayerLinksOn=true;"
    const val OFF = "window.__ssPlayerLinksOn=false;"

    /** Pages that belong in the instance; videos (/watch) and Shorts stay in the player. */
    private const val PAGES = "^/(@[^/]+|channel/|c/|user/|results|feed/|playlist|hashtag/|gaming|premium)"
    private val PAGE_RE = Regex(PAGES)

    val INSTALL: String =
        "(function(){if(window.top&&window.top!==window)return 'frame';if(window.__ssPlayerLinks)return 'ok';window.__ssPlayerLinks=true;" +
            "var re=new RegExp('$PAGES');" +
            "document.addEventListener('click',function(e){if(!window.__ssPlayerLinksOn||!window.$CHANNEL)return;" +
            "var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;if(!a)return;" +
            "var u;try{u=new URL(a.getAttribute('href'),location.href);}catch(x){return;}" +
            "if(u.hostname!==location.hostname||!/(^|\\.)youtube\\.com$/.test(u.hostname)||!re.test(u.pathname))return;" +
            "e.preventDefault();e.stopImmediatePropagation();window.$CHANNEL.postMessage(u.href);},true);return 'ok';})()"

    /** A page URL from the player tab's script: plain https on YouTube, one of [PAGES]. */
    fun accepts(url: String): Boolean {
        val u = url.takeIf { it.length < MAX_URL && it.startsWith("https://") }?.let { runCatching { java.net.URI(it) }.getOrNull() }
        return u != null && YouTubeSite.isYouTube(u.host) && PAGE_RE.containsMatchIn(u.rawPath.orEmpty())
    }

    private const val MAX_URL = 2048
}
