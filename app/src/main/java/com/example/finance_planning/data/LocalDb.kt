package com.example.finance_planning.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cache", primaryKeys = ["owner", "key"])
data class CacheRow(val owner: String, val key: String, val ciphertext: String, val savedAt: Long)

@Entity(tableName = "outbox", primaryKeys = ["owner", "id"])
data class PendingBatch(val owner: String, val id: String, val ciphertext: String,
    val createdAt: Long, val state: String = "PENDING", val error: String = "")

@Dao
interface LocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cache(row: CacheRow)
    @Query("SELECT * FROM cache WHERE owner = :owner AND `key` = :key")
    suspend fun cached(owner: String, key: String): CacheRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun enqueue(rows: List<PendingBatch>)
    @Query("SELECT * FROM outbox WHERE owner = :owner AND state = 'PENDING' ORDER BY createdAt, id")
    suspend fun pending(owner: String): List<PendingBatch>
    @Query("SELECT * FROM outbox WHERE owner = :owner ORDER BY createdAt DESC")
    suspend fun batches(owner: String): List<PendingBatch>
    @Query("UPDATE outbox SET state = :state, error = :error WHERE owner = :owner AND id = :id")
    suspend fun mark(owner: String, id: String, state: String, error: String)
    @Query("DELETE FROM cache WHERE owner = :owner") suspend fun clearCache(owner: String)
    @Query("DELETE FROM outbox WHERE owner = :owner") suspend fun clearBatches(owner: String)
}

@Database(entities = [CacheRow::class, PendingBatch::class], version = 1, exportSchema = true)
abstract class LocalDb : RoomDatabase() { abstract fun dao(): LocalDao }
