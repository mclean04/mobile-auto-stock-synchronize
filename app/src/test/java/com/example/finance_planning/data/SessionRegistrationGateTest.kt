package com.example.finance_planning.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionRegistrationGateTest {
    @Test fun logoutFencesQueuedRefreshAndDrainsInflightBeforeRevoke() = runBlocking {
        var pending = false
        val gate = SessionRegistrationGate { pending }
        val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val inflight = async { runCatching { gate.register {
            events += "register"; started.complete(Unit); response.await(); events += "registered"
        } } }
        started.await()
        val queued = async { runCatching { gate.register { events += "queued-register" } } }
        val logout = async { gate.logout({ pending = true }) { events += "revoke"; events += "clear" } }
        yield(); assertTrue(pending); assertEquals(listOf("register"), events)
        response.complete(Unit)
        assertTrue(inflight.await().isFailure); assertTrue(queued.await().isFailure); logout.await()
        assertEquals(listOf("register", "registered", "revoke", "clear"), events)
    }

    @Test fun failedOrCancelledRevokeKeepsFenceAndLocalSessionUntilDeliberateRetry() = runBlocking {
        for (failure in listOf(java.io.IOException("unknown"), CancellationException("cancelled"))) {
            var pending = false; var localSession = true; var revoked = false
            val gate = SessionRegistrationGate { pending }
            val result = runCatching { gate.logout({ pending = true }) { throw failure } }
            assertSame(failure, result.exceptionOrNull())
            assertTrue(localSession); assertTrue(pending)
            assertTrue(runCatching { gate.register { error("must not register") } }.isFailure)
            gate.logout({ pending = true }) { revoked = true; localSession = false }
            assertTrue(revoked); assertFalse(localSession)
        }
    }

    @Test fun refreshReadsCurrentTokenOnlyWhenItOwnsRegistrationLock() = runBlocking {
        var token = "old"; val gate = SessionRegistrationGate { false }
        val firstStarted = CompletableDeferred<Unit>(); val firstDone = CompletableDeferred<Unit>()
        val tokens = mutableListOf<String>()
        val first = async { gate.register { tokens += token; firstStarted.complete(Unit); firstDone.await() } }
        firstStarted.await()
        val refresh = async { gate.register { tokens += token } }
        yield(); token = "new"; firstDone.complete(Unit)
        first.await(); refresh.await()
        assertEquals(listOf("old", "new"), tokens)
    }
}
