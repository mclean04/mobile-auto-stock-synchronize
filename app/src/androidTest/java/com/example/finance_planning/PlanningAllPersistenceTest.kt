package com.example.finance_planning

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finance_planning.core.*
import com.example.finance_planning.data.CacheRow
import com.example.finance_planning.data.LocalDb
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** Uses an isolated database and fake pages; never calls Backend or DNSE. */
@RunWith(AndroidJUnit4::class)
class PlanningAllPersistenceTest {
    private fun page(id: String, next: String? = null, total: Int = 1): JSONObject {
        val source = JSONObject().put("source_id", "test-sheet-0001").put("source_generation", 7)
        val row = JSONObject().put("intent_id", id).put("record_kind", "CANONICAL")
            .put("time_status", "EXACT").put("scheduled_at", "2026-09-20T10:00:00+07:00")
            .put("source_context", source)
        return JSONObject().put("contract_version", "2.0").put("snapshot_id", "s1")
            .put("source_context", source).put("total_count", total)
            .put("items", JSONArray().put(row)).put("next_cursor", next ?: JSONObject.NULL)
    }

    @Test fun completeSnapshotSurvivesRoomReopenAndCannotLeakToAnotherOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "planning-all-test-" + UUID.randomUUID() + ".db"
        var db = Room.databaseBuilder(context, LocalDb::class.java, name).build()
        try {
            var calls = 0
            fun store(owner: String) = PlanningAllStore(
                { db.dao().cached(owner, "planning_all")?.let { JSONObject(it.ciphertext) } },
                { cursor ->
                    calls++
                    if (cursor == null) page("a", "next", 2) else page("b", total = 2)
                },
                { snapshot -> db.withTransaction {
                    db.dao().cache(CacheRow(owner, "planning_all", snapshot.toString(), 123))
                } })
            assertNull(store("owner-a").local())
            assertEquals(0, calls)
            store("owner-a").refresh()
            assertEquals(2, calls)
            db.close()
            db = Room.databaseBuilder(context, LocalDb::class.java, name).build()
            val restored = store("owner-a").local()!!
            assertEquals(2, restored.getJSONArray("items").length())
            val now = Instant.parse("2026-09-20T03:00:00Z")
            assertEquals(2, PlanningTimeline.rows(restored, PlanningSection.UPCOMING, now).size)
            assertTrue(PlanningTimeline.rows(restored, PlanningSection.HISTORY, now).isEmpty())
            assertEquals(2, PlanningTimeline.rows(restored, PlanningSection.HISTORY, now.plusSeconds(1)).size)
            assertEquals(2, calls)
            assertNull(store("owner-b").local())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun pageFailureAndRoomTransactionRollbackKeepPreviousSnapshotAndTimestamp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "planning-all-rollback-" + UUID.randomUUID() + ".db"
        val db = Room.databaseBuilder(context, LocalDb::class.java, name).build()
        try {
            val old = CacheRow("owner-a", "planning_all", page("old").toString(), 100)
            db.dao().cache(old)
            for (failPage in listOf(true, false)) {
                val store = PlanningAllStore(
                    { db.dao().cached("owner-a", "planning_all")?.let { JSONObject(it.ciphertext) } },
                    { cursor ->
                        assertEquals(old, db.dao().cached("owner-a", "planning_all"))
                        if (cursor == null) page("a", "next", 2)
                        else if (failPage) throw IOException("offline") else page("b", total = 2)
                    },
                    { snapshot -> db.withTransaction {
                        db.dao().cache(CacheRow("owner-a", "planning_all", snapshot.toString(), 200))
                        throw IOException("commit interrupted")
                    } })
                assertTrue(runCatching { store.refresh() }.isFailure)
                assertEquals(old, db.dao().cached("owner-a", "planning_all"))
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
