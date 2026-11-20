package com.streamshell.core.notify

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A monitored channel. [source]: imported (Takeout/NewPipe), found in the YouTube subscriptions sync, or both. */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val title: String,
    val source: String,
    val bell: Boolean = true,
    val favorite: Boolean = false,
    /** Complete syncs in a row the channel was missing from (see [NotifyRules.missedAfterSync]). */
    val missedSyncs: Int = 0,
    val lastSeenInSync: Long? = null,
    /** Last time its feed was read successfully; null = never (first check only remembers). */
    val lastChecked: Long? = null
)

/** One discovered upload / stream. Primary key: video id + kind — the same event is never notified twice. */
@Entity(tableName = "events", primaryKeys = ["videoId", "kind"])
data class EventEntity(
    val videoId: String,
    val kind: String,
    val channelId: String,
    val channelTitle: String,
    val title: String,
    val publishedAt: Long,
    val scheduledAt: Long? = null,
    val discoveredAt: Long,
    /** When the notification was shown; null = only remembered (not wanted, muted, or first check). */
    val notifiedAt: Long? = null
)

/** One subscriptions sync: when, how many channels, complete or partial, changes, error. */
@Entity(tableName = "sync_runs")
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val channels: Int,
    val complete: Boolean,
    val added: Int,
    val removed: Int,
    val error: String? = null
)

/** One background check of the feeds. */
@Entity(tableName = "check_runs")
data class CheckRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val channels: Int,
    val failed: Int,
    val notified: Int,
    val error: String? = null,
    /** New uploads found in this run (0.7.1). */
    @androidx.room.ColumnInfo(defaultValue = "0") val found: Int = 0,
    /** The run's numbers in words: feeds, new, skipped and why, shown (0.7.1). */
    val detail: String? = null
)

@Dao
interface NotifyDao {
    @Query("SELECT * FROM channels ORDER BY favorite DESC, title COLLATE NOCASE")
    fun channels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels")
    suspend fun channelsNow(): List<ChannelEntity>

    @Upsert
    suspend fun upsertChannels(c: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteChannels(ids: List<String>)

    @Query("UPDATE channels SET bell = :on WHERE id = :id")
    suspend fun setBell(id: String, on: Boolean)

    @Query("UPDATE channels SET favorite = :on WHERE id = :id")
    suspend fun setFavorite(id: String, on: Boolean)

    @Query("UPDATE channels SET lastChecked = :at WHERE id = :id")
    suspend fun checked(id: String, at: Long)

    @Query("SELECT videoId FROM events WHERE channelId = :channelId")
    suspend fun knownVideos(channelId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(e: EventEntity): Long

    @Query("SELECT * FROM events WHERE kind = 'UPCOMING' AND scheduledAt IS NOT NULL")
    suspend fun upcoming(): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE videoId = :videoId AND kind = :kind")
    suspend fun has(videoId: String, kind: String): Int

    @Query("UPDATE events SET notifiedAt = :at WHERE videoId = :videoId AND kind = :kind")
    suspend fun markNotified(videoId: String, kind: String, at: Long)

    @Query("SELECT * FROM events WHERE notifiedAt IS NOT NULL ORDER BY notifiedAt DESC LIMIT :limit")
    fun history(limit: Int = HISTORY): Flow<List<EventEntity>>

    @Query("DELETE FROM events WHERE discoveredAt < :before")
    suspend fun pruneEvents(before: Long)

    @Insert
    suspend fun insertSync(r: SyncRunEntity)

    @Query("SELECT * FROM sync_runs ORDER BY at DESC LIMIT 1")
    fun lastSync(): Flow<SyncRunEntity?>

    @Query("SELECT * FROM sync_runs ORDER BY at DESC LIMIT 1")
    suspend fun lastSyncNow(): SyncRunEntity?

    @Insert
    suspend fun insertCheck(r: CheckRunEntity)

    @Query("SELECT * FROM check_runs ORDER BY at DESC LIMIT 1")
    fun lastCheck(): Flow<CheckRunEntity?>

    @Query("DELETE FROM sync_runs WHERE at < :before")
    suspend fun pruneSyncs(before: Long)

    @Query("DELETE FROM check_runs WHERE at < :before")
    suspend fun pruneChecks(before: Long)

    companion object {
        const val HISTORY = 100
    }
}

@Database(entities = [ChannelEntity::class, EventEntity::class, SyncRunEntity::class, CheckRunEntity::class], version = 2, exportSchema = true)
abstract class NotifyDatabase : RoomDatabase() {
    abstract fun dao(): NotifyDao

    companion object {
        @Volatile private var instance: NotifyDatabase? = null

        /** 0.7.1: per-run numbers of a check. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE check_runs ADD COLUMN found INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE check_runs ADD COLUMN detail TEXT")
            }
        }

        fun get(context: Context): NotifyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NotifyDatabase::class.java, "notifications.db").addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
