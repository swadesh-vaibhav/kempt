package com.kempt.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** A single app the user has chosen to block. */
@Entity(tableName = "block_rules")
data class BlockRule(
    @PrimaryKey val packageName: String,
    val enabled: Boolean = true
)

/**
 * An accountability event recorded on-device. These are the only things that ever
 * leave the phone (via [com.kempt.app.sync.AccountabilityService]); raw usage data
 * never does. Kept locally too so the log survives while the partner is unreachable.
 */
@Entity(tableName = "block_events")
data class BlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    /** The blocked app involved, when the event is about one. */
    val packageName: String? = null,
    val at: Long = System.currentTimeMillis(),
    /** True once this event has been accepted by the backend. */
    val synced: Boolean = false
) {
    companion object {
        const val LOCK_ARMED = "lock_armed"
        const val LOCK_DISARMED = "lock_disarmed"
        /** A blocked app was brought to the foreground during an active lock. */
        const val BLOCK_ENFORCED = "block_enforced"
        const val UNLOCK_SUCCESS = "unlock_success"
        const val UNLOCK_FAILED = "unlock_failed"
        /** Usage access was revoked while a lock was active — a tamper signal. */
        const val USAGE_ACCESS_LOST = "usage_access_lost"
        /** The monitor was re-armed after a reboot. */
        const val BOOT_REARM = "boot_rearm"
    }
}

@Dao
interface BlockRuleDao {
    @Query("SELECT * FROM block_rules")
    fun observeAll(): Flow<List<BlockRule>>

    @Query("SELECT packageName FROM block_rules WHERE enabled = 1")
    suspend fun enabledPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: BlockRule)

    @Query("DELETE FROM block_rules WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface BlockEventDao {
    @Query("SELECT * FROM block_events ORDER BY at DESC LIMIT 100")
    fun observeRecent(): Flow<List<BlockEvent>>

    @Query("SELECT * FROM block_events WHERE synced = 0 ORDER BY at ASC")
    suspend fun unsynced(): List<BlockEvent>

    @Insert
    suspend fun insert(event: BlockEvent): Long

    @Query("UPDATE block_events SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

@Database(
    entities = [BlockRule::class, BlockEvent::class],
    version = 1,
    exportSchema = false
)
abstract class KemptDatabase : RoomDatabase() {
    abstract fun blockRuleDao(): BlockRuleDao
    abstract fun blockEventDao(): BlockEventDao
}
