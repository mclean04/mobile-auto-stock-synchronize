package com.example.finance_planning

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finance_planning.data.CacheRow
import com.example.finance_planning.data.LocalDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPersistenceTest {
    @Test fun notificationStreamUpdatesDeduplicatesAndSeparatesAccounts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "notification-test-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, LocalDb::class.java, name).build()
        try {
            val dao = db.dao()
            assertTrue(dao.observeNotifications("A").first().isEmpty())
            dao.cache(CacheRow("B", "notification:other", "private-B", 1))
            dao.cache(CacheRow("A", "notification-opened:event", "opened", 2))
            dao.cache(CacheRow("A", "notification:event", "version1", 3))
            val received = withTimeout(5000) { dao.observeNotifications("A").first { it.size == 1 } }
            assertEquals("version1", received.single().ciphertext)
            dao.cache(CacheRow("A", "notification:event", "version2", 4))
            val updated = withTimeout(5000) {
                dao.observeNotifications("A").first { it.singleOrNull()?.ciphertext == "version2" }
            }
            assertEquals(1, updated.size)
            db.close()
            db = Room.databaseBuilder(context, LocalDb::class.java, name).build()
            assertEquals("version2", db.dao().observeNotifications("A").first().single().ciphertext)
            db.dao().clearCache("A")
            assertTrue(db.dao().observeNotifications("A").first().isEmpty())
            assertEquals("private-B", db.dao().observeNotifications("B").first().single().ciphertext)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
