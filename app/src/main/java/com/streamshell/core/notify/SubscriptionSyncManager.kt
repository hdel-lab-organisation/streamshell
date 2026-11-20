package com.streamshell.core.notify

import com.streamshell.core.watch.Channel
import com.streamshell.core.watch.WatchRepository
import com.streamshell.core.watch.WatchRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Result of merging one subscriptions sync into the user's channels. */
data class SyncOutcome(val channels: List<Channel>, val rows: List<ChannelEntity>, val removed: List<String>, val added: Int, val complete: Boolean)

/**
 * Subscriptions sync rules (pure, unit-tested).
 *
 * - A channel found on YouTube's subscriptions page and not known yet is added (source SYNC).
 * - An imported channel found there becomes BOTH.
 * - Unsubscribe detection, deliberately careful: only a channel that came from the sync alone (SYNC) and is missing
 *   from [NotifyRules.MISSED_SYNCS_TO_REMOVE] COMPLETE syncs in a row is removed. A partial sync (the page still offered
 *   more, timed out, or found nothing) never counts. An imported channel (IMPORT, BOTH) is never removed — BOTH falls
 *   back to IMPORT.
 */
object SyncRules {
    fun apply(watch: List<Channel>, rows: Map<String, ChannelEntity>, found: List<Channel>, completeSync: Boolean, now: Long): SyncOutcome {
        // Nothing found = signed out or the page changed: never a complete sync, nothing is removed.
        val complete = completeSync && found.isNotEmpty()
        val foundIds = found.map { it.id }.toHashSet()
        val keep = ArrayList<Channel>()
        val out = ArrayList<ChannelEntity>()
        val removed = ArrayList<String>()
        watch.forEach { c ->
            val row = rows[c.id] ?: ChannelEntity(c.id, c.title, ChannelSource.IMPORT.name)
            val seen = c.id in foundIds
            var source = if (seen && row.source != ChannelSource.SYNC.name) ChannelSource.BOTH.name else row.source
            var missed = NotifyRules.missedAfterSync(row.missedSyncs, seen, complete)
            if (complete && !seen && missed >= NotifyRules.MISSED_SYNCS_TO_REMOVE) {
                if (source == ChannelSource.SYNC.name) {
                    removed += c.id
                    return@forEach
                }
                if (source == ChannelSource.BOTH.name) source = ChannelSource.IMPORT.name
                missed = 0
            }
            keep += c
            out += row.copy(title = c.title, source = source, missedSyncs = missed, lastSeenInSync = if (seen) now else row.lastSeenInSync)
        }
        val known = watch.map { it.id }.toHashSet()
        var added = 0
        found.distinctBy { it.id }.filter { it.id !in known }.forEach { c ->
            if (keep.size >= WatchRules.MAX_CHANNELS) return@forEach
            keep += c
            added++
            val row = rows[c.id] ?: ChannelEntity(c.id, c.title, ChannelSource.SYNC.name)
            out += row.copy(title = c.title, source = ChannelSource.SYNC.name, missedSyncs = 0, lastSeenInSync = now)
        }
        return SyncOutcome(keep, out, removed, added, complete)
    }

    /** Next automatic sync: twice a day after a good one, after an hour when the last one failed. */
    fun due(last: SyncRunEntity?, now: Long): Boolean = when {
        last == null -> true
        last.error != null -> now - last.at >= RETRY_MS
        else -> now - last.at >= com.streamshell.core.watch.SubscriptionSync.INTERVAL_MS
    }

    const val RETRY_MS = 60L * 60 * 1000
}

/** Stores a sync's result: the user's channel list (DataStore), the engine's channels (Room) and the sync record. */
class SubscriptionSyncManager(private val dao: NotifyDao, private val watch: WatchRepository) {
    suspend fun record(found: List<Channel>, complete: Boolean, error: String?, now: Long = System.currentTimeMillis()): SyncRunEntity {
        if (error != null) {
            return SyncRunEntity(at = now, channels = 0, complete = false, added = 0, removed = 0, error = error).also { dao.insertSync(it) }
        }
        var outcome: SyncOutcome? = null
        val rows = dao.channelsNow().associateBy { it.id }
        watch.update { w ->
            val o = SyncRules.apply(w.channels, rows, found, complete, now)
            outcome = o
            w.copy(channels = o.channels, muted = w.muted - o.removed.toSet())
        }
        val o = outcome ?: return SyncRunEntity(at = now, channels = 0, complete = false, added = 0, removed = 0, error = "not saved").also { dao.insertSync(it) }
        dao.upsertChannels(o.rows)
        if (o.removed.isNotEmpty()) dao.deleteChannels(o.removed)
        val run = SyncRunEntity(
            at = now,
            channels = found.size,
            complete = o.complete,
            added = o.added,
            removed = o.removed.size,
            error = if (found.isEmpty()) "no channels on YouTube's page (signed out?)" else null
        )
        dao.insertSync(run)
        return run
    }

    companion object {
        private val runningFlow = MutableStateFlow(false)

        /** A sync is running right now (shown in the notification settings). */
        val running: StateFlow<Boolean> = runningFlow.asStateFlow()

        fun setRunning(on: Boolean) {
            runningFlow.value = on
        }
    }
}
