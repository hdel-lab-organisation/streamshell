package com.streamshell.core.notify

import com.streamshell.core.watch.Channel
import com.streamshell.core.watch.WatchData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7.1: a whole check with a fake database, fake feeds and a fake Android (no network). */
class NotificationEngineTest {
    private class FakeDao : NotifyDao {
        val ch = LinkedHashMap<String, ChannelEntity>()
        val ev = LinkedHashMap<String, EventEntity>()
        val checks = ArrayList<CheckRunEntity>()
        private val chFlow = MutableStateFlow<List<ChannelEntity>>(emptyList())
        private fun k(v: String, kind: String) = "$v:$kind"

        override fun channels(): Flow<List<ChannelEntity>> = chFlow
        override suspend fun channelsNow() = ch.values.toList()
        override suspend fun upsertChannels(c: List<ChannelEntity>) = c.forEach { ch[it.id] = it }
        override suspend fun deleteChannels(ids: List<String>) = ids.forEach { ch.remove(it) }
        override suspend fun setBell(id: String, on: Boolean) {
            ch[id]?.let { ch[id] = it.copy(bell = on) }
        }
        override suspend fun setFavorite(id: String, on: Boolean) {
            ch[id]?.let { ch[id] = it.copy(favorite = on) }
        }
        override suspend fun checked(id: String, at: Long) {
            ch[id]?.let { ch[id] = it.copy(lastChecked = at) }
        }
        override suspend fun knownVideos(channelId: String) = ev.values.filter { it.channelId == channelId }.map { it.videoId }
        override suspend fun insertEvent(e: EventEntity): Long = if (ev.containsKey(k(e.videoId, e.kind))) {
            -1L
        } else {
            ev[k(e.videoId, e.kind)] = e
            1L
        }
        override suspend fun upcoming() = ev.values.filter { it.kind == "UPCOMING" && it.scheduledAt != null }
        override suspend fun has(videoId: String, kind: String) = if (ev.containsKey(k(videoId, kind))) 1 else 0
        override suspend fun markNotified(videoId: String, kind: String, at: Long) {
            ev[k(videoId, kind)]?.let { ev[k(videoId, kind)] = it.copy(notifiedAt = at) }
        }
        override fun history(limit: Int): Flow<List<EventEntity>> = MutableStateFlow(emptyList())
        override suspend fun pruneEvents(before: Long) = Unit
        override suspend fun insertSync(r: SyncRunEntity) = Unit
        override fun lastSync(): Flow<SyncRunEntity?> = MutableStateFlow(null)
        override suspend fun lastSyncNow(): SyncRunEntity? = null
        override suspend fun insertCheck(r: CheckRunEntity) {
            checks += r
        }
        override fun lastCheck(): Flow<CheckRunEntity?> = MutableStateFlow<List<CheckRunEntity>>(emptyList()).map { null }
        override suspend fun pruneSyncs(before: Long) = Unit
        override suspend fun pruneChecks(before: Long) = Unit
    }

    private val a = "UC" + "a".repeat(22)
    private val b = "UC" + "b".repeat(22)
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()
    private fun feed(vararg v: Pair<String, Long>) = "<feed><author><name>Chan</name></author>" +
        v.joinToString("") { (id, t) -> "<entry><yt:videoId>$id</yt:videoId><title>T $id</title><published>${iso(t)}</published></entry>" } + "</feed>"

    @Test fun firstCheckRemembersThenNewVideoIsShown() = runBlocking {
        val dao = FakeDao()
        var feeds = mapOf(a to feed("aaaaaaaaaa1" to now - hour))
        val engine = NotificationEngine(dao, fetch = { url -> feeds[url.substringAfter("channel_id=")] ?: error("404") }, classify = { WatchPageInfo.UNKNOWN })
        val watch = WatchData(channels = listOf(Channel(a, "A")))
        val shown = ArrayList<EventEntity>()
        val r1 = engine.run(NotifyPrefs(), watch, now) { Delivery(it).also { d -> shown += d.shown } }
        assertEquals(0, r1.delivery.shown.size)
        assertEquals(1, r1.stats.firstCheck)

        feeds = mapOf(a to feed("aaaaaaaaaa2" to now + hour, "aaaaaaaaaa1" to now - hour))
        val r2 = engine.run(NotifyPrefs(), watch, now + 2 * hour) { Delivery(it).also { d -> shown += d.shown } }
        assertEquals(listOf("aaaaaaaaaa2"), shown.map { it.videoId })
        assertEquals(1, r2.stats.found)
        assertNotNull(dao.ev["aaaaaaaaaa2:VIDEO"]?.notifiedAt)
        assertEquals(1, dao.checks.last().notified)
        assertTrue(dao.checks.last().detail!!.contains("shown 1"))
    }

    @Test fun blockedNotificationsAreNotCountedAsNotified() = runBlocking {
        val dao = FakeDao()
        var feeds = mapOf(a to feed("aaaaaaaaaa1" to now - hour))
        val engine = NotificationEngine(dao, fetch = { url -> feeds[url.substringAfter("channel_id=")] ?: error("404") }, classify = { WatchPageInfo.UNKNOWN })
        val watch = WatchData(channels = listOf(Channel(a, "A")))
        engine.run(NotifyPrefs(), watch, now) { Delivery(it) }
        feeds = mapOf(a to feed("aaaaaaaaaa2" to now + hour))
        engine.run(NotifyPrefs(), watch, now + 2 * hour) { Delivery(emptyList(), "permission not granted") }
        assertNull(dao.ev["aaaaaaaaaa2:VIDEO"]?.notifiedAt)
        assertEquals(0, dao.checks.last().notified)
        assertEquals("not shown: permission not granted", dao.checks.last().error)
        assertTrue(dao.checks.last().detail!!.contains("NOT SHOWN: permission not granted"))
    }

    @Test fun mutedChannelAndFallbackFeed() = runBlocking {
        val dao = FakeDao()
        var round = 0
        val engine = NotificationEngine(
            dao,
            fetch = { url ->
                when {
                    url.contains("channel_id=$a") -> feed(if (round == 0) "aaaaaaaaaa1" to now - hour else "aaaaaaaaaa2" to now + hour)

                    url.contains("channel_id=$b") -> error("HTTP 404")

                    // channel feed down …
                    url.contains("playlist_id=UU" + b.substring(2)) -> feed(if (round == 0) "bbbbbbbbbb1" to now - hour else "bbbbbbbbbb2" to now + hour)

                    // … uploads list works
                    else -> error("unexpected $url")
                }
            },
            classify = { WatchPageInfo.UNKNOWN }
        )
        val watch = WatchData(channels = listOf(Channel(a, "A"), Channel(b, "B")), muted = setOf(a))
        engine.run(NotifyPrefs(), watch, now) { Delivery(it) }
        round = 1
        val shown = ArrayList<EventEntity>()
        val r = engine.run(NotifyPrefs(), watch, now + 2 * hour) { Delivery(it).also { d -> shown += d.shown } }
        assertEquals(listOf("bbbbbbbbbb2"), shown.map { it.videoId }) // a is muted
        assertEquals(1, r.stats.channelOff)
        assertEquals(1, r.stats.fallbackUsed)
        assertEquals(0, r.stats.feedsFailed)
    }

    @Test fun liveIsClassified() = runBlocking {
        val dao = FakeDao()
        var feeds = mapOf(a to feed("aaaaaaaaaa1" to now - hour))
        val engine = NotificationEngine(dao, fetch = { url -> feeds[url.substringAfter("channel_id=")] ?: error("404") }, classify = { WatchPageInfo(live = true, upcoming = false, scheduledStart = null, known = true) })
        val watch = WatchData(channels = listOf(Channel(a, "A")))
        engine.run(NotifyPrefs(), watch, now) { Delivery(it) }
        feeds = mapOf(a to feed("aaaaaaaaaa2" to now + hour))
        val shown = ArrayList<EventEntity>()
        engine.run(NotifyPrefs(live = false), watch, now + 2 * hour) { Delivery(it).also { d -> shown += d.shown } }
        assertTrue(shown.isEmpty()) // "live" category off
        assertEquals("LIVE", dao.ev.values.first { it.videoId == "aaaaaaaaaa2" }.kind)
    }

    @Test fun uploadsFeed() {
        assertEquals("https://www.youtube.com/feeds/videos.xml?playlist_id=UU" + "a".repeat(22), NotifyRules.uploadsFeedOf(a))
        assertNull(NotifyRules.uploadsFeedOf("bad"))
    }
}
