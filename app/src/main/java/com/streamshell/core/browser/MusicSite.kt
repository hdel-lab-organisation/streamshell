package com.streamshell.core.browser

/**
 * YouTube Music in StreamShell (0.6.2). The page keeps its own top bar (search, account) and player; with "YouTube app
 * mode" on, its bottom tab bar is hidden because StreamShell's bar already holds Home, Explore, Library and search —
 * the same arrangement as on YouTube.
 */
object MusicSite {
    const val ORIGIN = "https://music.youtube.com"
    val origins = setOf(ORIGIN)
    const val STYLE_ID = "streamshell-compact-music"

    const val CSS = "ytmusic-pivot-bar-renderer{display:none!important}" +
        "ytmusic-app-layout{--ytmusic-pivot-bar-height:0px!important}" +
        "ytmusic-app-layout[is-bauhaus-sidenav-enabled] #content{padding-bottom:0!important}"

    val INSTALL: String = "(function(){if(document.getElementById('$STYLE_ID'))return 'ok';var s=document.createElement('style');s.id='$STYLE_ID';" +
        "s.textContent=" + org.json.JSONObject.quote(CSS) + ";(document.head||document.documentElement).appendChild(s);return 'ok';})()"

    fun isMusic(host: String?): Boolean = SiteApp.of(host) == SiteApp.YOUTUBE_MUSIC

    /** Wait for YouTube Music's own reaction to a tap on a title before StreamShell opens the link (ms). */
    const val TAP_FALLBACK_MS = 350

    /**
     * Taps on YouTube Music (0.7.2). User: a tap on a song's picture opens at once, a tap on its title only with a firm
     * press; the log had no trace for Music. This page script
     * - writes "touch N ms on …" / "click on …" lines to the diagnostics (with "trace everything", like on YouTube);
     * - when a short, still tap on the title link of a list entry got no reaction from Music at all — no click event and
     *   no new address within [TAP_FALLBACK_MS] — it opens that link itself. Taps Music handles stay untouched.
     */
    fun tapScript(trace: Boolean): String = "(function(){window.__ssMusicTrace=" + trace + ";if(window.__ssMusicTap)return 'ok';window.__ssMusicTap=1;var down=null;" +
        "function post(m){if(window.__ssMusicTrace&&window.streamShellSponsor)window.streamShellSponsor.postMessage('diag|'+String(m).slice(0,300));}" +
        "function kind(el){if(!el||!el.closest)return 'page';var a=el.closest('a,button,[role=button],tp-yt-paper-icon-button');var t=a||el;" +
        "var c='';var p=el;while(p&&p!==document.body&&p.tagName){var n=p.tagName.toLowerCase();if(n.indexOf('-')>0){c=n;break;}p=p.parentElement;}" +
        "var k=a&&a.tagName==='A'?(/watch/.test(a.getAttribute('href')||'')?'song link':'link'):a?'button':'element';" +
        "return k+' <'+(t.tagName||'?').toLowerCase()+'>'+(c?' in '+c:'');}" +
        "document.addEventListener('pointerdown',function(e){down={x:e.clientX,y:e.clientY,t:Date.now(),clicked:false};},true);" +
        "document.addEventListener('click',function(e){if(down)down.clicked=true;post('click on '+kind(e.target));},true);" +
        "document.addEventListener('pointerup',function(e){if(!down)return;var d=down;down=null;var dx=e.clientX-d.x,dy=e.clientY-d.y,dt=Date.now()-d.t,dist=Math.sqrt(dx*dx+dy*dy);" +
        "post((dist<10?(dt>=500?'long press':'touch'):'drag '+Math.round(dx)+','+Math.round(dy))+' '+dt+' ms on '+kind(e.target)+' at '+Math.round(e.clientX)+','+Math.round(e.clientY));" +
        "if(dist>=10||dt>=500||!e.target||!e.target.closest)return;" +
        "var a=e.target.closest('a[href]');var item=e.target.closest('ytmusic-responsive-list-item-renderer,ytmusic-two-row-item-renderer');" +
        "if(!a||!item||!item.contains(a))return;var href=location.href;" +
        "setTimeout(function(){if(d.clicked||location.href!==href)return;post('music tap: no reaction, link opened');try{a.click();}catch(x){}}," + TAP_FALLBACK_MS + ");},true);" +
        "return 'ok';})()"

    /**
     * Video id of YouTube Music's player page (music.youtube.com/watch?v=ID…), else null. Separate from
     * SponsorBlockApi.videoIdOf, which leaves Music out on purpose (no SponsorBlock, no resume, no Watch later there):
     * up to 0.7.0 the Music checks used that one and were never true (log of 2026-11-03 08:03).
     */
    fun playerVideoId(url: String?): String? {
        if (!isMusic(UrlHosts.hostOf(url))) return null
        val path = url.orEmpty().substringAfter("://").substringAfter('/', "").substringBefore('#')
        if (!path.startsWith("watch")) return null
        return path.substringAfter('?', "").split('&').firstOrNull { it.startsWith("v=") }?.substring(2)?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
    }
}

/**
 * The page's own media session (YouTube and YouTube Music both register "next track" / "previous track" handlers for
 * the system's media controls). A document-start script keeps a copy of the handlers the page registers, so the car's
 * buttons can call exactly what the page offers; without a handler a page button is clicked, else nothing happens.
 * Read-only otherwise: title and artist come from the page's media-session metadata.
 */
object MediaSessionScripts {
    val origins = YouTubeSite.origins + MusicSite.origins

    const val CAPTURE = "(function(){if(window.__ssMS)return;window.__ssMS={};try{var ms=navigator.mediaSession;if(!ms||!ms.setActionHandler)return;" +
        "var orig=ms.setActionHandler.bind(ms);ms.setActionHandler=function(a,h){try{window.__ssMS[a]=h;}catch(e){}return orig(a,h);};}catch(e){}})();"

    const val NEXT = "nexttrack"
    const val PREVIOUS = "previoustrack"

    /** Runs [action] ("nexttrack" / "previoustrack"); answers "handler", "button" or "none". */
    fun action(action: String): String {
        val a = if (action == NEXT) NEXT else PREVIOUS
        val buttons = if (a == NEXT) ".next-button,.ytp-next-button,button.player-control-next" else ".previous-button,.ytp-prev-button,button.player-control-previous"
        return "(function(a){var h=window.__ssMS&&window.__ssMS[a];if(typeof h==='function'){try{h({action:a});return 'handler';}catch(e){}}" +
            "var b=document.querySelector('$buttons');if(b){b.click();return 'button';}" +
            "if(a==='previoustrack'){var v=document.querySelector('video,audio');if(v){v.currentTime=0;return 'start';}}return 'none';})('$a')"
    }

    /** "title\u0001artist" of what plays, from the page's media session, else "". */
    const val NOW_PLAYING = "(function(){try{var m=navigator.mediaSession&&navigator.mediaSession.metadata;if(m&&m.title)return m.title+'\\u0001'+(m.artist||'');}catch(e){}return '';})()"

    /** (title, artist) from [NOW_PLAYING]'s answer, or null. */
    fun parseNowPlaying(raw: String?): Pair<String, String>? {
        val s = raw?.let { r -> runCatching { org.json.JSONTokener(r).nextValue() as? String }.getOrNull() }.orEmpty()
        val title = s.substringBefore('\u0001').trim().take(MAX)
        val artist = s.substringAfter('\u0001', "").trim().take(MAX)
        return (title to artist).takeIf { title.isNotEmpty() }
    }

    private const val MAX = 120
}
