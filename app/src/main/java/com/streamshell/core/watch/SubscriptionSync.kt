package com.streamshell.core.watch

import org.json.JSONObject

/**
 * Notifications for new videos, like the YouTube app (0.6.7).
 *
 * Channels: the user's YouTube subscriptions, read from YouTube's own "All subscriptions" page in StreamShell's
 * signed-in browser (the page is opened in the background and its channel list is read — no cookies or tokens are
 * read or sent anywhere), merged with the imported channels. New videos: YouTube's public channel feeds, checked every
 * 30 minutes in the background. Everything stays on the device.
 */
object SubscriptionSync {
    const val PAGE = "https://m.youtube.com/feed/channels"

    /** At most once per this interval (the app must be open; the page is read in the background). */
    const val INTERVAL_MS = 12L * 60 * 60 * 1000

    /**
     * Page script: {channelId: name} of every channel on the page — YouTube's page data (ytInitialData, an object or a
     * JSON string on the mobile site), else the links to /channel/UC… on the page.
     */
    const val SCRIPT = "(function(){var out={},n=0;function add(id,t){if(n<4000&&id&&/^UC[A-Za-z0-9_-]{22}$/.test(id)&&t&&!out[id]){out[id]=String(t).slice(0,200);n++;}}" +
        "function txt(x){return x&&(x.simpleText||(x.runs&&x.runs[0]&&x.runs[0].text)||(x.content))||'';}" +
        "function walk(o,d){if(!o||typeof o!=='object'||d>60||n>=4000)return;" +
        "if(o.channelId)add(o.channelId,txt(o.title)||txt(o.displayName)||txt(o.shortBylineText));" +
        "if(o.text&&o.navigationEndpoint&&o.navigationEndpoint.browseEndpoint)add(o.navigationEndpoint.browseEndpoint.browseId,o.text);" +
        "for(var k in o){if(Object.prototype.hasOwnProperty.call(o,k))walk(o[k],d+1);}}" +
        "try{var d=window.ytInitialData;if(typeof d==='string')d=JSON.parse(d);walk(d,0);}catch(e){}" +
        "try{var r=document.querySelectorAll('ytm-channel-list-item-renderer,ytd-channel-renderer,ytm-compact-channel-renderer');for(var j=0;j<r.length;j++){walk(r[j].data||(r[j].__data&&r[j].__data.data),0);}}catch(e){}" +
        "try{var a=document.querySelectorAll('a[href*=\"/channel/UC\"]');for(var i=0;i<a.length;i++){var m=a[i].getAttribute('href').match(/\\/channel\\/(UC[A-Za-z0-9_-]{22})/);" +
        "if(m)add(m[1],(a[i].getAttribute('aria-label')||a[i].textContent||'').trim());}}catch(e){}" +
        "return JSON.stringify(out);})()"

    /**
     * Scrolls the page to its end (YouTube then loads the next part of the list) and answers "links:more" — how many
     * channel links the page shows, and 1 while YouTube's "load more" element is still on the page.
     */
    const val SCROLL = "(function(){var e=document.scrollingElement||document.documentElement;e.scrollTop=e.scrollHeight;window.scrollTo(0,e.scrollHeight);" +
        "var n=document.querySelectorAll('a[href*=\"/channel/UC\"],a[href^=\"/@\"],ytm-channel-list-item-renderer,ytd-channel-renderer').length;" +
        "var m=document.querySelector('ytm-continuation-item-renderer,ytd-continuation-item-renderer,continuation-item-renderer')?1:0;return n+':'+m;})()"

    /** Scroll rounds at most (each loads one more part of a long list). */
    const val MAX_SCROLLS = 25

    data class Progress(val count: Int, val more: Boolean)

    /** [SCROLL]'s answer; null when unreadable. */
    fun parseProgress(raw: String?): Progress? {
        val text = runCatching { org.json.JSONTokener(raw ?: return null).nextValue() as? String }.getOrNull() ?: return null
        val parts = text.split(':')
        val n = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return Progress(n, parts.getOrNull(1) == "1")
    }

    /**
     * Whether a sync may be called complete: the page had nothing more to load and the list stopped growing for two
     * rounds. Anything else (timeout, rounds used up, unreadable page) is a partial sync.
     */
    fun complete(last: Progress?, stableRounds: Int): Boolean = last != null && !last.more && stableRounds >= 2

    /** Channels from [SCRIPT]'s answer (the WebView returns it as a JSON string). */
    fun parse(raw: String?): List<Channel> {
        val text = runCatching { org.json.JSONTokener(raw ?: return emptyList()).nextValue() as? String }.getOrNull() ?: return emptyList()
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        return o.keys().asSequence().mapNotNull { id ->
            val name = o.optString(id).trim()
            if (Regex("UC[A-Za-z0-9_-]{22}").matches(id) && name.isNotEmpty()) Channel(id, name) else null
        }.take(WatchRules.MAX_CHANNELS).toList()
    }

    /** The user's channels plus the subscribed ones not yet known (existing names and order kept). */
    fun merge(existing: List<Channel>, synced: List<Channel>): List<Channel> {
        val known = existing.map { it.id }.toHashSet()
        return (existing + synced.filter { it.id !in known }.distinctBy { it.id }).take(WatchRules.MAX_CHANNELS)
    }
}
