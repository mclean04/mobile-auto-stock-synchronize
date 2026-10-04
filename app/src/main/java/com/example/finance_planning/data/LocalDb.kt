package com.example.finance_planning.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cache", primaryKeys = ["owner", "key"])
data class CacheRow(val owner: String, val key: String, val ciphertext: String, val savedAt: Long)

data class StoredNotification(@Embedded val event: CacheRow, val opened: Boolean)

@Entity(tableName = "outbox", primaryKeys = ["owner", "id"])
data class PendingBatch(val owner: String, val id: String, val ciphertext: String,
    val createdAt: Long, val state: String = "PENDING", val error: String = "")

@Dao
interface LocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cache(row: CacheRow)
    @Query("SELECT * FROM cache WHERE owner = :owner AND `key` = :key")
    suspend fun cached(owner: String, key: String): CacheRow?
    @Query("SELECT * FROM cache WHERE owner = :owner AND `key` LIKE 'placed-report:%' ORDER BY savedAt")
    suspend fun placedReports(owner: String): List<CacheRow>
    @Query("SELECT * FROM cache WHERE owner = :owner AND `key` LIKE 'notification:%' ORDER BY savedAt DESC")
    fun observeNotifications(owner: String): Flow<List<CacheRow>>
    @Query("""SELECT n.*, (o.`key` IS NOT NULL) AS opened FROM cache n
        LEFT JOIN cache o ON o.owner = n.owner
            AND o.`key` = 'notification-opened:' || substr(n.`key`, 14)
        WHERE n.owner = :owner AND n.`key` LIKE 'notification:%'
        ORDER BY n.savedAt DESC""")
    fun observeNotificationInbox(owner: String): Flow<List<StoredNotification>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun enqueue(rows: List<PendingBatch>)
    @Query("SELECT * FROM outbox WHERE owner = :owner AND state = 'PENDING' ORDER BY createdAt, id")
    suspend fun pending(owner: String): List<PendingBatch>
    @Query("SELECT * FROM outbox WHERE owner = :owner ORDER BY createdAt DESC")
    suspend fun batches(owner: String): List<PendingBatch>
    @Query("UPDATE outbox SET state = :state, error = :error WHERE owner = :owner AND id = :id")
    suspend fun mark(owner: String, id: String, state: String, error: String)
    @Query("DELETE FROM cache WHERE owner = :owner AND `key` = :key")
    suspend fun deleteCache(owner: String, key: String)
    @Query("DELETE FROM cache WHERE owner = :owner") suspend fun clearCache(owner: String)
    @Query("DELETE FROM outbox WHERE owner = :owner") suspend fun clearBatches(owner: String)
}

@Database(entities = [CacheRow::class, PendingBatch::class], version = 1, exportSchema = true)
abstract class LocalDb : RoomDatabase() { abstract fun dao(): LocalDao }
