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

@Dao
interface BlockRuleDao {
    @Query("SELECT * FROM block_rules")
    fun observeAll(): Flow<List<BlockRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: BlockRule)

    @Query("DELETE FROM block_rules WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Database(entities = [BlockRule::class], version = 1, exportSchema = false)
abstract class KemptDatabase : RoomDatabase() {
    abstract fun blockRuleDao(): BlockRuleDao
}
