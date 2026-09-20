package com.example.finance_planning

import com.example.finance_planning.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class PlanningAllTest {
    private val now = Instant.parse("2026-09-20T03:00:00Z")
    private fun source(generation: Int = 7) = JSONObject()
        .put("source_id", "source-sheet-0001").put("source_generation", generation)
    private fun row(id: String, time: String? = now.toString(), status: String = "EXACT",
                    kind: String = "CANONICAL") = JSONObject()
        .put("intent_id", id).put("record_kind", kind).put("time_status", status)
        .put("scheduled_at", time ?: JSONObject.NULL).put("source_context", source())
    private fun page(rows: List<JSONObject>, cursor: String? = null, total: Int = rows.size) =
        JSONObject().put("contract_version", "2.0").put("source_context", source())
            .put("snapshot_id", "snapshot-1").put("total_count", total)
            .put("items", JSONArray(rows)).put("next_cursor", cursor ?: JSONObject.NULL)
    private fun ids(snapshot: JSONObject, section: PlanningSection, at: Instant = now) =
        PlanningTimeline.rows(snapshot, section, at).map { it.getString("intent_id") }

    @Test fun openingEmptyOrPersistedStoreNeverFetchesEvenAfterRecreation() = runBlocking {
        var db: String? = null
        var calls = 0
        fun open() = PlanningAllStore({ db?.let(::JSONObject) },
            { calls++; page(emptyList()) }, { db = it.toString() })
        repeat(4) { assertNull(open().local()) }
        assertEquals(0, calls)
        open().refresh()
        repeat(4) {
            val local = open().local()!!
            PlanningSection.entries.forEach { section -> assertTrue(ids(local, section).isEmpty()) }
        }
        assertEquals(1, calls)
    }

    @Test fun explicitRefreshCollectsEveryPageBeforeOneAtomicReplacement() = runBlocking {
        var db = page(listOf(row("old"))).toString()
        val before = db
        val requests = mutableListOf<String?>()
        var saves = 0
        val store = PlanningAllStore({ JSONObject(db) }, { cursor ->
            assertEquals(before, db)
            requests.add(cursor)
            when (cursor) {
                null -> page(listOf(row("a")), "opaque +/=", 3)
                "opaque +/=" -> page(listOf(row("b")), "last", 3)
                "last" -> page(listOf(row("c")), null, 3)
                else -> error("Unexpected cursor")
            }
        }, { saves++; db = it.toString() })
        val all = store.refresh()
        assertEquals(listOf(null, "opaque +/=", "last"), requests)
        assertEquals(1, saves)
        assertEquals(listOf("a", "b", "c"), ids(all, PlanningSection.ALL))
        assertEquals(ids(all, PlanningSection.ALL), ids(store.local()!!, PlanningSection.ALL))
    }

    @Test fun secondPageOfflineOrConflictOrCancellationRetainsPreviousSnapshot() = runBlocking {
        for (failure in listOf(IOException("offline"), IllegalStateException("409 snapshot conflict"),
            CancellationException("cancelled"))) {
            var db = page(listOf(row("old"))).toString()
            val before = db
            val store = PlanningAllStore({ JSONObject(db) }, { cursor ->
                if (cursor == null) page(listOf(row("a")), "next", 2) else throw failure
            }, { db = it.toString() })
            assertTrue(runCatching { store.refresh() }.isFailure)
            assertEquals(before, db)
        }
    }

    @Test fun changedSourceSnapshotCountOrDuplicateRowNeverReplacesCache() = runBlocking {
        val badPages = listOf(
            page(listOf(row("b")), total = 2).put("source_context", source(8)),
            page(listOf(row("b")), total = 2).put("snapshot_id", "snapshot-2"),
            page(listOf(row("b")), total = 3),
            page(listOf(row("b").put("source_context", source(8))), total = 2),
            page(listOf(row("a")), total = 2),
            page(emptyList(), total = 2),
            page(listOf(row("b")), total = 2).apply { remove("next_cursor") },
            page(listOf(row("b")), total = 2).put("next_cursor", "next"),
            page(listOf(row("b")), total = 2).put("next_cursor", 123),
            page(listOf(row("b")), total = 2).put("contract_version", "unknown")
        )
        for (bad in badPages) {
            var writes = 0
            val old = page(listOf(row("old")))
            val store = PlanningAllStore({ old }, { cursor ->
                if (cursor == null) page(listOf(row("a")), "next", 2) else bad
            }, { writes++ })
            assertTrue(bad.toString(), runCatching { store.refresh() }.isFailure)
            assertEquals(0, writes)
            assertSame(old, store.local())
        }
    }

    @Test fun successfulEmptyRefreshClearsPreviousSnapshot() = runBlocking {
        var db = page(listOf(row("old")))
        val store = PlanningAllStore({ db }, { page(emptyList()) }, { db = it })
        store.refresh()
        assertTrue(ids(db, PlanningSection.ALL).isEmpty())
    }

    @Test fun failedAtomicCommitDoesNotReturnSuccessfulRefresh() = runBlocking {
        val old = page(listOf(row("old")))
        val store = PlanningAllStore({ old }, { page(listOf(row("new"))) },
            { throw IOException("commit failed") })
        assertTrue(runCatching { store.refresh() }.isFailure)
        assertSame(old, store.local())
    }

    @Test fun boundaryUsesOneInstantAcrossOffsetsAndNanoseconds() {
        val all = page(listOf(row("before", now.minusNanos(1).toString()),
            row("equal", "2026-09-20T10:00:00+07:00"), row("after", now.plusNanos(1).toString())))
        assertEquals(listOf("equal", "after"), ids(all, PlanningSection.UPCOMING))
        assertEquals(listOf("before"), ids(all, PlanningSection.HISTORY))
        assertEquals(3, ids(all, PlanningSection.ALL).size)
    }

    @Test fun clockMovingForwardOrBackwardRepartitionsSameSnapshotWithoutNetwork() = runBlocking {
        val all = page(listOf(row("one")))
        val original = all.toString()
        val store = PlanningAllStore({ all }, { fail("Clock must not fetch"); all },
            { fail("Clock must not persist") })
        for (at in listOf(now, now.plusSeconds(1), now.minusSeconds(1), now)) {
            val local = store.local()!!
            val waiting = ids(local, PlanningSection.UPCOMING, at)
            val history = ids(local, PlanningSection.HISTORY, at)
            assertEquals(1, waiting.size + history.size)
            assertEquals(if (at > now) emptyList<String>() else listOf("one"), waiting)
        }
        assertEquals(original, all.toString())
    }

    @Test fun missingDateOnlyInvalidAndTimezoneLessAreAllOnly() {
        val rows = listOf(row("missing", null, "MISSING"),
            row("date", null, "DATE_ONLY").put("scheduled_date", "2026-09-20"),
            row("invalid", "not-a-time", "INVALID"), row("unqualified", "2026-09-20T10:00:00"),
            row("lying-date", now.toString(), "DATE_ONLY"), row("null-exact", null),
            row("unknown-status", now.toString(), "NEW_STATUS"))
        val all = page(rows)
        assertEquals(rows.size, ids(all, PlanningSection.ALL).size)
        assertTrue(ids(all, PlanningSection.UPCOMING).isEmpty())
        assertTrue(ids(all, PlanningSection.HISTORY).isEmpty())
        rows.forEach { assertFalse(PlanningTimeline.mayOpenAction(PlanningSection.UPCOMING, it, now)) }
    }

    @Test fun everyAuthoringAndExecutionStateAndLegacySurvivesRefreshVerbatim() = runBlocking {
        val states = listOf("DRAFT", "APPROVED", "WITHDRAWN", "SUPERSEDED", "RESEARCH_ONLY")
        val executions = listOf("NOT_STARTED", "SUBMITTED", "FILLED", "CANCELLED", "EXPIRED", "UNKNOWN")
        val rows = states.flatMap { a -> executions.map { e ->
            row("$a:$e").put("authoring_state", a).put("execution_state", e)
        } } + row("legacy", null, "DATE_ONLY", "LEGACY")
            .put("legacy_status", "PLANNING ONLY").put("quantity", "")
            .put("legacy_fields", JSONObject().put("Ngày dự kiến", "2026-09-01"))
        val all = PlanningAllStore({ null }, { page(rows) }, {}).refresh()
        assertEquals(rows.size, all.getJSONArray("items").length())
        rows.forEachIndexed { i, row ->
            assertEquals(row.toString(), all.getJSONArray("items").getJSONObject(i).toString())
        }
    }

    @Test fun actionIsOnlyCanonicalExactUpcomingAndRechecksCurrentClock() {
        val future = row("future", now.plusSeconds(1).toString())
        assertTrue(PlanningTimeline.mayOpenAction(PlanningSection.UPCOMING, future, now))
        assertTrue(PlanningTimeline.mayOpenAction(PlanningSection.UPCOMING, row("equal"), now))
        assertFalse(PlanningTimeline.mayOpenAction(PlanningSection.ALL, future, now))
        assertFalse(PlanningTimeline.mayOpenAction(PlanningSection.HISTORY, future, now))
        assertFalse(PlanningTimeline.mayOpenAction(PlanningSection.UPCOMING, future, now.plusSeconds(2)))
        assertFalse(PlanningTimeline.mayOpenAction(PlanningSection.UPCOMING,
            row("legacy", now.toString(), kind = "LEGACY"), now))
    }
}
