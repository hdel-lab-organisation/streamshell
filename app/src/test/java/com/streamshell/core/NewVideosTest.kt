package com.streamshell.core

import com.streamshell.core.watch.Channel
import com.streamshell.core.watch.SubscriptionSync
import com.streamshell.core.watch.WatchData
import com.streamshell.core.watch.WatchSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.7: reading the subscriptions from YouTube's page, channel bells (the notification rules are in NotifyRulesTest). */
class NewVideosTest {
    private val a = "UC" + "a".repeat(22)
    private val b = "UC" + "b".repeat(22)

    @Test fun subscriptionsParseAndMerge() {
        // The WebView answers with the script's JSON string, quoted.
        val raw = org.json.JSONObject.quote("{\"$a\":\"Alpha\",\"$b\":\"Beta\",\"bad\":\"x\",\"UC123\":\"short\"}")
        val found = SubscriptionSync.parse(raw)
        assertEquals(setOf(Channel(a, "Alpha"), Channel(b, "Beta")), found.toSet())
        assertTrue(SubscriptionSync.parse(null).isEmpty())
        assertTrue(SubscriptionSync.parse("\"not json\"").isEmpty())
        val merged = SubscriptionSync.merge(listOf(Channel(a, "My name")), found)
        assertEquals(listOf(a, b), merged.map { it.id })
        assertEquals("My name", merged.first().title) // existing names kept
    }

    @Test fun mutedChannelsAreStored() {
        val d = WatchData(channels = listOf(Channel(a, "A")), muted = setOf(a, "not-a-channel"))
        val enc = WatchSerializer.encode(d)
        val back = WatchSerializer.decode { enc[it] }
        assertEquals(setOf(a), back.muted)
        assertTrue(WatchSerializer.decode { null }.muted.isEmpty())
    }
}
