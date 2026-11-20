package com.streamshell.core.notify

import com.streamshell.core.model.AppSettings
import com.streamshell.core.watch.Channel
import com.streamshell.core.watch.FeedVideo
import com.streamshell.core.watch.SubscriptionSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7.0 Notification Engine: rules, watch-page classification, subscriptions sync. */
class NotifyRulesTest {
    private val hour = 3_600_000L
    private val now = 1_000 * hour
    private val a = "UC" + "a".repeat(22)
    private val b = "UC" + "b".repeat(22)
    private val c = "UC" + "c".repeat(22)

    private fun v(id: String, t: Long) = FeedVideo(id.padEnd(11, 'x'), "Title $id", "Chan", t)

    @Test fun defaultsAsAgreed() {
        val p = AppSettings().notifyPrefs()
        assertTrue(p.master && p.videos && p.live && p.upcoming)
        assertFalse(p.community)
        assertEquals(30, AppSettings().notifyIntervalMin)
        assertFalse(p.wants(EventKind.COMMUNITY))
        assertFalse(p.copy(master = false).wants(EventKind.VIDEO))
    }

    @Test fun firstCheckOnlyRemembers() {
        assertTrue(NotifyRules.newUploads(listOf(v("a1", now - hour)), emptySet(), firstCheck = true, now = now).isEmpty())
    }

    @Test fun newUploadsAreUnknownAndRecent() {
        val vids = listOf(v("a1", now - hour), v("a2", now - 2 * hour), v("a3", now - 3 * 24 * hour))
        val r = NotifyRules.newUploads(vids, setOf(vids[1].videoId), firstCheck = false, now = now)
        assertEquals(listOf(vids[0].videoId), r.map { it.videoId }) // known and too old ones are left out
    }

    @Test fun bellAndFavourites() {
        val p = NotifyPrefs()
        assertTrue(NotifyRules.channelNotifies(bell = true, favorite = false, prefs = p))
        assertFalse(NotifyRules.channelNotifies(bell = false, favorite = true, prefs = p))
        assertFalse(NotifyRules.channelNotifies(bell = true, favorite = false, prefs = p.copy(favoritesOnly = true)))
        assertTrue(NotifyRules.channelNotifies(bell = true, favorite = true, prefs = p.copy(favoritesOnly = true)))
    }

    @Test fun upcomingWindow() {
        val start = now + hour
        assertFalse(NotifyRules.recheckUpcoming(start, now))
        assertTrue(NotifyRules.recheckUpcoming(start, start - 5 * 60_000))
        assertTrue(NotifyRules.recheckUpcoming(start, start + 11 * hour))
        assertFalse(NotifyRules.recheckUpcoming(start, start + 13 * hour))
        assertFalse(NotifyRules.recheckUpcoming(null, now))
    }

    @Test fun intervalIsClamped() {
        assertEquals(15, NotifyRules.clampInterval(1))
        assertEquals(30, NotifyRules.clampInterval(30))
        assertEquals(60, NotifyRules.clampInterval(50))
        assertEquals(180, NotifyRules.clampInterval(10_000))
    }

    @Test fun watchPageClassification() {
        assertEquals(WatchPageInfo.UNKNOWN, WatchPageInfo.parse(null))
        assertEquals(WatchPageInfo.UNKNOWN, WatchPageInfo.parse("<html>consent</html>"))
        assertEquals(EventKind.VIDEO, WatchPageInfo.parse("{\"videoDetails\":{\"isLiveContent\":false}}").kind)
        assertEquals(EventKind.LIVE, WatchPageInfo.parse("{\"videoDetails\":{\"isLiveNow\": true}}").kind)
        val up = WatchPageInfo.parse("{\"videoDetails\":{\"isUpcoming\":true},\"liveBroadcastDetails\":{\"scheduledStartTime\":\"1767225600\"}}")
        assertEquals(EventKind.UPCOMING, up.kind)
        assertEquals(1_767_225_600_000L, up.scheduledStart)
        assertNull(WatchPageInfo.parse("{\"videoDetails\":{}}").scheduledStart)
    }

    @Test fun eventKeysAreUniquePerKind() {
        assertTrue(NotifyRules.key("x", EventKind.UPCOMING) != NotifyRules.key("x", EventKind.LIVE))
    }

    // ------------------------------------------------------------ sync

    private fun row(id: String, source: ChannelSource, missed: Int = 0, fav: Boolean = false) = ChannelEntity(id, id, source.name, favorite = fav, missedSyncs = missed)

    @Test fun syncAddsNewAndMarksBoth() {
        val o = SyncRules.apply(listOf(Channel(a, "A")), mapOf(a to row(a, ChannelSource.IMPORT)), listOf(Channel(a, "A"), Channel(b, "B")), completeSync = true, now = now)
        assertEquals(listOf(a, b), o.channels.map { it.id })
        assertEquals(1, o.added)
        assertEquals(ChannelSource.BOTH.name, o.rows.first { it.id == a }.source)
        assertEquals(ChannelSource.SYNC.name, o.rows.first { it.id == b }.source)
        assertTrue(o.complete)
    }

    @Test fun unsubscribedSyncChannelGoesAfterTwoCompleteSyncs() {
        val watch = listOf(Channel(a, "A"), Channel(b, "B"))
        val rows = mapOf(a to row(a, ChannelSource.SYNC), b to row(b, ChannelSource.SYNC, fav = true))
        val first = SyncRules.apply(watch, rows, listOf(Channel(a, "A")), completeSync = true, now = now)
        assertEquals(listOf(a, b), first.channels.map { it.id }) // once missing: kept
        assertEquals(1, first.rows.first { it.id == b }.missedSyncs)
        assertTrue(first.rows.first { it.id == b }.favorite) // favourite kept
        val second = SyncRules.apply(first.channels, first.rows.associateBy { it.id }, listOf(Channel(a, "A")), completeSync = true, now = now)
        assertEquals(listOf(a), second.channels.map { it.id })
        assertEquals(listOf(b), second.removed)
    }

    @Test fun partialSyncNeverRemoves() {
        val watch = listOf(Channel(b, "B"))
        val rows = mapOf(b to row(b, ChannelSource.SYNC, missed = 5))
        val o = SyncRules.apply(watch, rows, listOf(Channel(a, "A")), completeSync = false, now = now)
        assertTrue(o.removed.isEmpty())
        assertFalse(o.complete)
        assertEquals(5, o.rows.first { it.id == b }.missedSyncs)
    }

    @Test fun emptySyncIsNeverComplete() {
        val o = SyncRules.apply(listOf(Channel(b, "B")), mapOf(b to row(b, ChannelSource.SYNC, missed = 1)), emptyList(), completeSync = true, now = now)
        assertFalse(o.complete)
        assertEquals(listOf(b), o.channels.map { it.id })
    }

    @Test fun importedChannelsAreNeverRemoved() {
        val watch = listOf(Channel(a, "A"), Channel(c, "C"))
        val rows = mapOf(a to row(a, ChannelSource.IMPORT, missed = 1), c to row(c, ChannelSource.BOTH, missed = 1))
        val o = SyncRules.apply(watch, rows, listOf(Channel(b, "B")), completeSync = true, now = now)
        assertTrue(o.removed.isEmpty())
        assertEquals(ChannelSource.IMPORT.name, o.rows.first { it.id == c }.source) // BOTH → IMPORT
        assertTrue(a in o.channels.map { it.id })
    }

    @Test fun syncDue() {
        assertTrue(SyncRules.due(null, now))
        val ok = SyncRunEntity(at = now - hour, channels = 3, complete = true, added = 0, removed = 0)
        assertFalse(SyncRules.due(ok, now))
        assertTrue(SyncRules.due(ok.copy(at = now - 13 * hour), now))
        assertTrue(SyncRules.due(ok.copy(error = "x"), now))
        assertFalse(SyncRules.due(ok.copy(error = "x", at = now - 10 * 60_000), now))
    }

    @Test fun scrollProgress() {
        assertEquals(SubscriptionSync.Progress(12, true), SubscriptionSync.parseProgress("\"12:1\""))
        assertEquals(SubscriptionSync.Progress(40, false), SubscriptionSync.parseProgress("\"40:0\""))
        assertNull(SubscriptionSync.parseProgress(null))
        assertNull(SubscriptionSync.parseProgress("\"x\""))
        assertFalse(SubscriptionSync.complete(SubscriptionSync.Progress(40, true), 5))
        assertFalse(SubscriptionSync.complete(SubscriptionSync.Progress(40, false), 1))
        assertTrue(SubscriptionSync.complete(SubscriptionSync.Progress(40, false), 2))
        assertFalse(SubscriptionSync.complete(null, 9))
    }
}
