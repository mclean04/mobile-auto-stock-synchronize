package com.example.finance_planning

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finance_planning.data.LocalDb
import com.example.finance_planning.data.PendingBatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboxPersistenceTest {
    @Test fun retryPayloadSurvivesDatabaseReopenAndIsOwnerScoped() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "outbox-test-" + java.util.UUID.randomUUID() + ".db"
        var db = Room.databaseBuilder(context, LocalDb::class.java, name).build()
        try {
            val expected = PendingBatch("owner-a", "fixed-batch", "test-only-ciphertext", 123)
            db.dao().enqueue(listOf(expected))
            db.close()
            db = Room.databaseBuilder(context, LocalDb::class.java, name).build()
            assertEquals(expected, db.dao().pending("owner-a").single())
            assertTrue(db.dao().pending("owner-b").isEmpty())
            db.dao().mark("owner-b", "fixed-batch", "COMMITTED", "")
            assertEquals(1, db.dao().pending("owner-a").size)
            db.dao().mark("owner-a", "fixed-batch", "REVIEW_REQUIRED", "HTTP 409")
            assertTrue(db.dao().pending("owner-a").isEmpty())
            assertEquals("test-only-ciphertext", db.dao().batches("owner-a").single().ciphertext)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
