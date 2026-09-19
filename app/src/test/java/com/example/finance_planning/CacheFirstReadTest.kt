package com.example.finance_planning

import com.example.finance_planning.core.CacheFirstRead
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CacheFirstReadTest {
    @Test fun firstLoadPersistsThenRestartAndResumeDoNotCallApi() = runBlocking {
        val db = mutableMapOf<String, List<String>>()
        var apiCalls = 0
        suspend fun open(force: Boolean = false) = CacheFirstRead.load(force,
            { db["owner:upcoming"] }, { apiCalls++; listOf("REE") }, { db["owner:upcoming"] = it })
        assertEquals(listOf("REE"), open())
        repeat(4) { assertEquals(listOf("REE"), open()) }
        assertEquals(1, apiCalls)
        open(true); assertEquals(2, apiCalls)
    }
    @Test fun cachedEmptyListDoesNotTriggerRepeatedRequests() = runBlocking {
        var calls = 0
        val result = CacheFirstRead.load(false, { emptyList<String>() }, { calls++; listOf("new") }, { fail("Unexpected write") })
        assertTrue(result.isEmpty()); assertEquals(0, calls)
    }
    @Test fun cacheKeysDoNotOverwriteOtherAccountsOrLists() = runBlocking {
        val db = mutableMapOf("owner:upcoming" to "old-upcoming", "owner:history" to "old-history", "other:history" to "private")
        val result = CacheFirstRead.load(true, { db["owner:history"] }, { "new-history" }, { db["owner:history"] = it })
        assertEquals("new-history", result)
        assertEquals("old-upcoming", db["owner:upcoming"])
        assertEquals("private", db["other:history"])
    }
    @Test fun failedRefreshPreservesLastSavedData() = runBlocking {
        var stored = "last-good"
        try {
            CacheFirstRead.load(true, { stored }, { throw IOException("offline") }, { stored = it })
            fail("Expected refresh failure")
        } catch (_: IOException) {}
        assertEquals("last-good", stored)
    }
    @Test fun forcedReadChecksServerEvenWhenCacheExists() = runBlocking {
        var calls = 0
        val checked = CacheFirstRead.load(true, { "stale-plan" }, { calls++; "new-plan" }, {})
        assertEquals("new-plan", checked); assertEquals(1, calls)
    }
}
