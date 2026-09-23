/**
 * @file
 * @brief Room database, entities, and DAOs for block rules and accountability events.
 */
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

/**
 * @brief A single app the user has chosen to block, stored in the @c block_rules table.
 *
 * @details A Kotlin @c data class auto-generates @c equals, @c hashCode, @c toString, and
 * @c copy from its constructor properties. Room maps each @c val to a column.
 *
 * @property packageName The app's package id; the primary key, so each app appears at most once.
 * @property enabled Whether this rule is currently active (defaults to @c true).
 */
@Entity(tableName = "block_rules")
data class BlockRule(
    @PrimaryKey val packageName: String,
    val enabled: Boolean = true
)

/**
 * @brief An accountability event recorded on-device, stored in the @c block_events table.
 *
 * @details These are the only things that ever leave the phone (via
 * @ref com.kempt.app.sync.AccountabilityService); raw usage data never does. They are also
 * kept locally so the log survives while the partner's backend is unreachable.
 *
 * @property id Auto-generated row id (0 until inserted).
 * @property type One of the event-type constants in the companion object.
 * @property packageName The blocked app involved, when the event is about one; otherwise @c null.
 * @property at Event time in epoch milliseconds; defaults to now.
 * @property synced @c true once the backend has accepted this event.
 */
@Entity(tableName = "block_events")
data class BlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val packageName: String? = null,
    val at: Long = System.currentTimeMillis(),
    val synced: Boolean = false
) {
    /** @brief Stable string tags persisted in @ref type; safe to send to the backend. */
    companion object {
        /** @brief A lock was armed (one-tap lockdown). */
        const val LOCK_ARMED = "lock_armed"
        /** @brief A lock was disarmed. */
        const val LOCK_DISARMED = "lock_disarmed"
        /** @brief A blocked app was brought to the foreground during an active lock. */
        const val BLOCK_ENFORCED = "block_enforced"
        /** @brief A correct passcode was entered and the lock ended. */
        const val UNLOCK_SUCCESS = "unlock_success"
        /** @brief A wrong passcode was entered. */
        const val UNLOCK_FAILED = "unlock_failed"
        /** @brief Usage access was revoked while a lock was active — a tamper signal. */
        const val USAGE_ACCESS_LOST = "usage_access_lost"
        /** @brief The monitor was re-armed after a reboot. */
        const val BOOT_REARM = "boot_rearm"
        /** @brief Uninstall protection (device admin) was turned on. */
        const val DEVICE_ADMIN_ENABLED = "device_admin_enabled"
        /**
         * @brief The user asked to remove uninstall protection (device admin) — a tamper signal.
         * @details Deactivating the admin is the first step to uninstalling Kempt, so this is the
         * "trying to break out" moment reported to the partner.
         */
        const val DEVICE_ADMIN_DISABLE_REQUESTED = "device_admin_disable_requested"
        /** @brief Uninstall protection (device admin) was turned off. */
        const val DEVICE_ADMIN_DISABLED = "device_admin_disabled"
    }
}

/**
 * @brief Data-access object for @ref BlockRule rows.
 *
 * @details Room generates this interface's implementation at compile time from the SQL in
 * each annotation. @c suspend functions run off the main thread; @c Flow-returning functions
 * emit a fresh list whenever the underlying table changes.
 */
@Dao
interface BlockRuleDao {
    /**
     * @brief Observes all block rules, re-emitting whenever the table changes.
     * @return A @c Flow of the full rule list.
     */
    @Query("SELECT * FROM block_rules")
    fun observeAll(): Flow<List<BlockRule>>

    /**
     * @brief Reads the package names of all currently enabled rules.
     * @return The enabled package names (what the monitor enforces).
     */
    @Query("SELECT packageName FROM block_rules WHERE enabled = 1")
    suspend fun enabledPackages(): List<String>

    /**
     * @brief Inserts a rule, replacing any existing row with the same package (an upsert).
     * @param rule The rule to store.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: BlockRule)

    /**
     * @brief Deletes the rule for a package, if present.
     * @param packageName The app to unblock.
     */
    @Query("DELETE FROM block_rules WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

/** @brief Data-access object for @ref BlockEvent rows. */
@Dao
interface BlockEventDao {
    /**
     * @brief Observes the 100 most recent events, newest first.
     * @return A @c Flow of recent events for the activity log.
     */
    @Query("SELECT * FROM block_events ORDER BY at DESC LIMIT 100")
    fun observeRecent(): Flow<List<BlockEvent>>

    /**
     * @brief Reads all events not yet accepted by the backend, oldest first.
     * @return The unsynced events, for flushing when connectivity returns.
     */
    @Query("SELECT * FROM block_events WHERE synced = 0 ORDER BY at ASC")
    suspend fun unsynced(): List<BlockEvent>

    /**
     * @brief Inserts one event.
     * @param event The event to store.
     * @return The new row's auto-generated id.
     */
    @Insert
    suspend fun insert(event: BlockEvent): Long

    /**
     * @brief Marks the given events as synced.
     * @param ids Row ids the backend has accepted.
     */
    @Query("UPDATE block_events SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

/**
 * @brief The app's Room database, wiring the entities to their DAOs.
 *
 * @details Declared @c abstract because Room generates the concrete subclass at build time.
 * The DAO-returning methods are the entry points other code uses to read and write.
 */
@Database(
    entities = [BlockRule::class, BlockEvent::class],
    version = 1,
    exportSchema = false
)
abstract class KemptDatabase : RoomDatabase() {
    /** @brief @return The DAO for block rules. */
    abstract fun blockRuleDao(): BlockRuleDao

    /** @brief @return The DAO for accountability events. */
    abstract fun blockEventDao(): BlockEventDao
}
