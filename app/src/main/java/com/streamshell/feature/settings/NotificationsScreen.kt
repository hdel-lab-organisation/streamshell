package com.streamshell.feature.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamshell.core.model.AppSettings
import com.streamshell.core.notify.ChannelEntity
import com.streamshell.core.notify.CheckRunEntity
import com.streamshell.core.notify.EventEntity
import com.streamshell.core.notify.EventKind
import com.streamshell.core.notify.NotificationDispatcher
import com.streamshell.core.notify.NotificationEngine
import com.streamshell.core.notify.NotifyDatabase
import com.streamshell.core.notify.NotifyRules
import com.streamshell.core.notify.SubscriptionSyncManager
import com.streamshell.core.notify.SyncRunEntity
import com.streamshell.core.settings.SettingsRepository
import com.streamshell.core.watch.WatchRepository
import com.streamshell.designsystem.GlassDivider
import com.streamshell.designsystem.GlassHeader
import com.streamshell.designsystem.GlassPanel
import com.streamshell.designsystem.Spacing
import com.streamshell.designsystem.StreamIcons
import com.streamshell.designsystem.StreamTheme
import com.streamshell.designsystem.ambientBackground
import com.streamshell.di.LibraryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val settings: AppSettings = AppSettings(),
    val channels: List<ChannelEntity> = emptyList(),
    val lastSync: SyncRunEntity? = null,
    val lastCheck: CheckRunEntity? = null,
    val history: List<EventEntity> = emptyList(),
    val syncing: Boolean = false,
    val permission: Boolean = true,
    val system: SystemStatus = SystemStatus(),
    val testResult: String? = null
)

/**
 * What Android allows (0.7.1): notifications per kind (null = allowed, else why not), whether StreamShell is exempt
 * from battery optimization, and Android's standby bucket (how often background work may run).
 */
data class SystemStatus(
    val blocked: Map<EventKind, String?> = emptyMap(),
    val batteryExempt: Boolean = true,
    val bucket: String? = null
)

data class NotificationsActions(
    val onUpdate: ((AppSettings) -> AppSettings) -> Unit = {},
    val onBell: (String, Boolean) -> Unit = { _, _ -> },
    val onFavorite: (String, Boolean) -> Unit = { _, _ -> },
    val onSyncNow: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onRefreshPermission: () -> Unit = {},
    val onTest: () -> Unit = {},
    val onBattery: () -> Unit = {}
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @LibraryStore store: DataStore<Preferences>
) : ViewModel() {
    private val dao = NotifyDatabase.get(context).dao()
    private val watch = WatchRepository(store)
    private val permission = MutableStateFlow(NotificationDispatcher.allowed(context))
    private val system = MutableStateFlow(systemStatus(context))
    private val testResult = MutableStateFlow<String?>(null)

    private data class Runs(val sync: SyncRunEntity?, val check: CheckRunEntity?, val syncing: Boolean, val permission: Boolean, val system: SystemStatus)

    private val runs = combine(dao.lastSync(), dao.lastCheck(), SubscriptionSyncManager.running, permission, system) { a, b, c, d, e -> Runs(a, b, c, d, e) }

    val state: StateFlow<NotificationsUiState> = combine(settings.settings, dao.channels(), dao.history(), runs, testResult) { s, ch, h, r, t ->
        NotificationsUiState(s, ch, r.sync, r.check, h, r.syncing, r.permission, r.system, t)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), NotificationsUiState())

    init {
        // The channel list shows right away, before the first background check.
        viewModelScope.launch { runCatching { NotificationEngine(dao).syncChannels(watch.data.first()) } }
    }

    fun update(f: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(f) }
    }

    /** The bell is the same as the Library's (muted channels); kept in both places. */
    fun setBell(id: String, on: Boolean) {
        viewModelScope.launch {
            watch.update { w -> w.copy(muted = if (on) w.muted - id else w.muted + id) }
            dao.setBell(id, on)
        }
    }

    fun setFavorite(id: String, on: Boolean) {
        viewModelScope.launch { dao.setFavorite(id, on) }
    }

    fun refreshPermission() {
        permission.value = NotificationDispatcher.allowed(context)
        system.value = systemStatus(context)
    }

    fun sendTest() {
        testResult.value = NotificationDispatcher.test(context)
        refreshPermission()
    }

    /** Android's own dialog "Let app always run in background?" (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). */
    fun requestBatteryExemption() {
        val pkg = context.packageName
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(ask) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private companion object {
        const val STOP_MS = 5000L
    }
}

@Composable
fun NotificationsScreen(state: NotificationsUiState, actions: NotificationsActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val s = state.settings
    val c = StreamTheme.colors
    val context = LocalContext.current
    // Again on every return to the app (after Android's battery or notification settings).
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        actions.onRefreshPermission()
        onPauseOrDispose { }
    }
    Box(modifier.fillMaxSize().ambientBackground(c).testTag("notifications-screen")) {
        Column(Modifier.fillMaxSize()) {
            GlassHeader("Notifications", onClose, backTag = "notifications-back")
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxSize().testTag("notifications-list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg)
                ) {
                    item {
                        GlassPanel(title = "Android") {
                            val blocked = state.system.blocked.values.filterNotNull().distinct()
                            ActionRow(
                                if (blocked.isEmpty()) "Notifications allowed" else "Notifications blocked",
                                if (blocked.isEmpty()) {
                                    "Android shows StreamShell's notifications (new videos, live, upcoming). Tap for Android's notification settings."
                                } else {
                                    blocked.joinToString(" · ") + ". Tap to open Android's notification settings."
                                },
                                "notify-permission"
                            ) { openAppNotificationSettings(context) }
                            ActionRow(
                                if (state.system.batteryExempt) "Runs in the background" else "Battery optimization is on",
                                if (state.system.batteryExempt) {
                                    "StreamShell is exempt from battery optimization; checks can run while the app is closed." + (state.system.bucket?.let { " Android standby group: $it." }.orEmpty())
                                } else {
                                    "Android may hold back the checks while StreamShell is closed. Tap and choose \"Allow\" (on some phones also set the app's battery use to \"Unrestricted\")." +
                                        (state.system.bucket?.let { " Standby group: $it." }.orEmpty())
                                },
                                "notify-battery"
                            ) { actions.onBattery() }
                            ActionRow("Send test notification", state.testResult ?: "Shows one notification in the \"New videos\" category right now.", "notify-test") { actions.onTest() }
                        }
                    }
                    item {
                        GlassPanel(title = "Notifications") {
                            SwitchRow(
                                "Notifications",
                                "New uploads of your channels are checked in the background, with a network connection and when Android allows it (battery saving can delay a check). Nothing is sent anywhere except the requests for YouTube's public pages.",
                                s.notifyEnabled,
                                tag = "notify-master"
                            ) { v -> actions.onUpdate { it.copy(notifyEnabled = v) } }
                            GlassDivider()
                            SwitchRow("New videos", null, s.newVideoNotifications, tag = "notify-videos") { v -> actions.onUpdate { it.copy(newVideoNotifications = v) } }
                            SwitchRow("Live stream started", "Shown with high priority.", s.notifyLive, tag = "notify-live") { v -> actions.onUpdate { it.copy(notifyLive = v) } }
                            SwitchRow("Upcoming live streams", "When a channel schedules a stream; a second notification when it goes live (if \"Live stream started\" is on).", s.notifyUpcoming, tag = "notify-upcoming") { v ->
                                actions.onUpdate { it.copy(notifyUpcoming = v) }
                            }
                            SwitchRow("Community posts", "Not available: YouTube offers no public, reliable source for community posts, and StreamShell does not scrape them.", false, tag = "notify-community") { }
                            GlassDivider()
                            ChipRow("Check every", "notify-interval", NotifyRules.INTERVALS_MIN.map { it to if (it < 60) "$it min" else "${it / 60} h" }, s.notifyIntervalMin) { v ->
                                actions.onUpdate { it.copy(notifyIntervalMin = v) }
                            }
                            SwitchRow("Favourites only", "Only channels marked with the star below notify.", s.notifyFavoritesOnly, tag = "notify-favorites-only") { v -> actions.onUpdate { it.copy(notifyFavoritesOnly = v) } }
                        }
                    }
                    item {
                        GlassPanel(title = "Status") {
                            val sync = state.lastSync
                            Line(
                                "Subscriptions sync",
                                when {
                                    state.syncing -> "Running…"
                                    sync == null -> "Not yet — open StreamShell while signed in to YouTube"
                                    sync.error != null -> "${ago(sync.at)}: failed — ${sync.error}"
                                    sync.complete -> "${ago(sync.at)}: complete · ${sync.channels} channels · ${sync.added} new · ${sync.removed} removed"
                                    else -> "${ago(sync.at)}: partial (the page may have had more) · ${sync.channels} channels · ${sync.added} new"
                                }
                            )
                            val check = state.lastCheck
                            Line(
                                "Last check",
                                when {
                                    check == null -> "Not yet"
                                    check.detail != null -> "${ago(check.at)}: ${check.channels} channels · ${check.detail}"
                                    check.error != null -> "${ago(check.at)}: ${check.error}"
                                    else -> "${ago(check.at)}: ${check.channels - check.failed} of ${check.channels} channels read · ${check.notified} notified"
                                }
                            )
                            ActionRow("Sync now", "Reads your subscriptions from YouTube now, then checks all channels once.", "notify-sync-now") { actions.onSyncNow() }
                        }
                    }
                    item {
                        GlassPanel(title = "Channels (${state.channels.size})") {
                            if (state.channels.isEmpty()) {
                                Line("No channels yet", "Import your subscriptions in the Library or sync them from YouTube.")
                            }
                        }
                    }
                    items(state.channels, key = { "ch-" + it.id }) { ch -> ChannelRow(ch, actions) }
                    item {
                        GlassPanel(title = "History") {
                            if (state.history.isEmpty()) Line("Nothing yet", null)
                        }
                    }
                    items(state.history, key = { "ev-" + it.videoId + it.kind }) { e -> HistoryRow(e, actions) }
                }
            }
        }
    }
}

@Composable
private fun Line(title: String, text: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = StreamTheme.colors.textSecondary)
    }
}

@Composable
private fun ChannelRow(ch: ChannelEntity, actions: NotificationsActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm).testTag("notify-channel-${ch.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ch.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (ch.source) {
                    "SYNC" -> "From YouTube"
                    "BOTH" -> "Imported · on YouTube"
                    else -> "Imported"
                },
                style = MaterialTheme.typography.bodySmall,
                color = StreamTheme.colors.textSecondary
            )
        }
        IconButton(onClick = { actions.onFavorite(ch.id, !ch.favorite) }, modifier = Modifier.testTag("notify-fav-${ch.id}")) {
            Icon(if (ch.favorite) StreamIcons.FavoriteOn else StreamIcons.FavoriteOff, if (ch.favorite) "Remove from favourites" else "Add to favourites")
        }
        IconButton(onClick = { actions.onBell(ch.id, !ch.bell) }, modifier = Modifier.testTag("notify-bell-${ch.id}")) {
            Icon(if (ch.bell) StreamIcons.BellOn else StreamIcons.BellOff, if (ch.bell) "Turn notifications off" else "Turn notifications on")
        }
    }
}

@Composable
private fun HistoryRow(e: EventEntity, actions: NotificationsActions) {
    val kind = runCatching { EventKind.valueOf(e.kind) }.getOrDefault(EventKind.VIDEO)
    ActionRow(e.title, "${NotificationDispatcher.label(kind)} · ${e.channelTitle} · ${ago(e.notifiedAt ?: e.discoveredAt)}", "notify-event-${e.videoId}") {
        actions.onOpen("https://m.youtube.com/watch?v=${e.videoId}")
    }
}

private fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val min = (now - at) / MINUTE
    return when {
        min < 1 -> "just now"
        min < HOUR_MIN -> "$min min ago"
        min < DAY_MIN -> "${min / HOUR_MIN} h ago"
        else -> "${min / DAY_MIN} d ago"
    }
}

private fun openAppNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private const val MINUTE = 60_000L
private const val HOUR_MIN = 60L
private const val DAY_MIN = 24L * 60

/** Android's view of StreamShell's notifications and background work right now. */
internal fun systemStatus(context: Context): SystemStatus {
    val blocked = listOf(EventKind.VIDEO, EventKind.LIVE, EventKind.UPCOMING).associateWith { NotificationDispatcher.blockedReason(context, it) }
    val pm = context.getSystemService(android.os.PowerManager::class.java)
    val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    val bucket = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        context.getSystemService(android.app.usage.UsageStatsManager::class.java)?.appStandbyBucket?.let { b ->
            when {
                b <= android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                b <= android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
                b <= android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                b <= android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> "rare (checks only a few times a day)"
                else -> "restricted (checks about once a day)"
            }
        }
    } else {
        null
    }
    return SystemStatus(blocked, exempt, bucket)
}
