package com.example.finance_planning

import com.example.finance_planning.core.LocalPlanningHealthCheck
import com.example.finance_planning.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.SocketException

class LocalPlanningHealthCheckTest {
    @Before fun text() { TestText.install("en") }

    private fun transport(requests: MutableList<Request>, body: String, status: Int = 200) =
        Transport(OkHttpClient.Builder()
            .dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = error("No network allowed") })
            .addInterceptor {
                requests += it.request()
                Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("fixture").body(body.toResponseBody()).build()
            }.build())

    @Test fun explicitHealthCheckHasNoCredentialsOrSessionMutation() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = transport(requests, """{"status":"ok","storage":"firestore"}""")
        val result = LocalPlanningHealthCheck.check { api.planningHealth() }
        assertTrue(result.contains("expected Planning/Firestore"))
        assertTrue(result.contains("FCM have not been checked"))
        assertEquals(1, requests.size)
        assertEquals("GET", requests.single().method)
        assertEquals("/health", requests.single().url.encodedPath)
        assertNull(requests.single().body)
        assertNull(requests.single().header("Authorization"))
        assertNull(requests.single().header("X-Firebase-AppCheck"))
    }

    @Test fun stockOrMalformedHealthIsNotReportedAsPlanningReady() = runBlocking {
        for (body in listOf("""{"status":"ok"}""", "<html>fixture-service</html>",
            """{"status":"ok","storage":"another-service"}""")) {
            val requests = mutableListOf<Request>()
            val api = transport(requests, body)
            val result = LocalPlanningHealthCheck.check { api.planningHealth() }
            assertTrue(result, result.contains("does not match"))
            assertTrue(result.contains("no session was resent"))
            assertEquals(listOf("GET"), requests.map { it.method })
            assertFalse(result.contains("fixture-service"))
        }
    }

    @Test fun httpFailureIsNotMislabelledAsAnUnreachableSocketOrEchoed() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = transport(requests, """{"detail":"SYNTHETIC-PRIVATE-DETAIL"}""", 404)
        val result = LocalPlanningHealthCheck.check { api.planningHealth() }
        assertTrue(result.contains("HTTP 404"))
        assertFalse(result.contains("SYNTHETIC-PRIVATE"))
        assertEquals(1, requests.size)
    }

    @Test fun socketFailureGivesConnectionAdviceWithoutExposingMessageOrRetrying() = runBlocking {
        var calls = 0
        val result = LocalPlanningHealthCheck.check {
            calls++
            throw PlanningCallFailure(PlanningMutationOutcome.NOT_APPLICABLE, SocketException("SYNTHETIC-SENSITIVE"))
        }
        assertEquals(1, calls)
        assertTrue(result.contains("USB route"))
        assertTrue(result.contains("does not establish an authentication failure"))
        assertFalse(result.contains("SYNTHETIC-SENSITIVE"))
    }

    @Test fun cancellationAndRetiredContextPropagateWithoutRetry() = runBlocking {
        for (failure in listOf(CancellationException("cancelled"), SupersededNetworkContext())) {
            var calls = 0
            val error = runCatching { LocalPlanningHealthCheck.check { calls++; throw failure } }.exceptionOrNull()
            assertSame(failure, error)
            assertEquals(1, calls)
        }
    }
}
