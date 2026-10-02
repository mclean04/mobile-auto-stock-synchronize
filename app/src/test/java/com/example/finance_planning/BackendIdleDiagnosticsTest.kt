package com.example.finance_planning

import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Loopback-only failure injection; demonstrates diagnostics, not the user's unobserved device failure. */
class BackendIdleDiagnosticsTest {
    @Test fun reusedConnectionFailureIsObservedWithoutReplayingReadOrMutation() = runBlocking {
        for (method in listOf("GET", "POST")) {
            val server = MockWebServer()
            server.start(java.net.InetAddress.getLoopbackAddress(), 0)
            val logs = mutableListOf<String>()
            val client = OkHttpClient.Builder().singleExchange()
                .readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS)
                .eventListenerFactory { BackendExchangeDiagnostics(logs::add) }.build()
            try {
                val service = httpService(server.url("/").toString(), client)
                server.enqueue(MockResponse().setBody("{}"))
                service.get(server.url("/warm").toString(), emptyMap()).body()!!.use { it.string() }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                val result = runCatching {
                    service.request(server.url("/synthetic-idle").toString(), method, emptyMap(),
                        if (method == "POST") "{}".toRequestBody() else null).body()?.use { it.string() }
                }
                assertTrue(result.exceptionOrNull() is IOException)
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals(2, server.requestCount)
                if (BuildConfig.DEBUG) {
                    assertTrue(logs.any { "connection=pooled" in it && "headers_sent=true" in it })
                    assertTrue(logs.any { "java.io.EOFException" in it && "stage=call_failed" in it })
                    assertFalse(logs.any { "synthetic-idle" in it || server.hostName in it })
                } else assertTrue(logs.isEmpty())
            } finally {
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                server.shutdown()
            }
        }
    }
}
