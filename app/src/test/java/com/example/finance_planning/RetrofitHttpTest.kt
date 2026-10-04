package com.example.finance_planning

import com.example.finance_planning.core.AppFailure
import com.example.finance_planning.core.Contracts
import com.example.finance_planning.network.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RetrofitHttpTest {
    @Before fun text() { TestText.install() }

    @Test fun backendPreservesVerbsEncodedQueryJsonHeadersAndErrorCodes() = runBlocking {
        val requests = mutableListOf<Request>()
        var code = 200
        var raw = "{\"ok\":true}"
        val transport = Transport(OkHttpClient.Builder().addInterceptor {
            requests.add(it.request())
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(code).message("unit")
                .body(raw.toResponseBody()).build()
        }.build())
        val url = Contracts.BACKEND + "/v1/sync/batches?cursor=a%2Bb%2Fc"
        val payload = JSONObject().put("text", "đặt lệnh")
        for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE")) {
            val body = payload.takeIf { method in setOf("POST", "PUT", "PATCH") }
            assertEquals(raw, transport.request(url, method, mapOf("Authorization" to "unit-only"), body))
            val request = requests.last()
            assertEquals(method, request.method)
            assertEquals("cursor=a%2Bb%2Fc", request.url.encodedQuery)
            assertEquals("unit-only", request.header("Authorization"))
            if (body != null) {
                val sink = okio.Buffer(); request.body!!.writeTo(sink)
                assertEquals(body.toString(), sink.readUtf8())
            } else assertNull(request.body)
        }
        code = 401; raw = "{\"detail\":\"invalid_firebase_token\"}"
        val error = runCatching { transport.request(url) }.exceptionOrNull() as HttpFailure
        assertEquals(401, error.status); assertEquals("invalid_firebase_token", error.code)
        code = 204; raw = ""
        assertEquals("{}", transport.request(url, "DELETE"))
        val count = requests.size
        assertTrue(runCatching { transport.request("http://example.invalid/") }.exceptionOrNull() is AppFailure)
        assertEquals(count, requests.size)
    }

    @Test fun backendBoundsPayloadAndStreamedSuccess() = runBlocking {
        var calls = 0
        val transport = Transport(OkHttpClient.Builder().addInterceptor {
            calls++
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("unit")
                .body("x".repeat(4 * 1024 * 1024 + 1).toResponseBody()).build()
        }.build())
        assertTrue(runCatching { transport.request(Contracts.BACKEND, "POST", body =
            JSONObject().put("data", "x".repeat(2 * 1024 * 1024))) }.exceptionOrNull() is AppFailure)
        assertEquals(0, calls)
        assertTrue(runCatching { transport.request(Contracts.BACKEND) }.exceptionOrNull() is AppFailure)
        assertEquals(1, calls)
    }

    @Test fun serviceNeverReplaysMutationOrForwardsRedirectCredentials() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val service = httpService(server.url("/").toString(), OkHttpClient.Builder().singleExchange().build())
            for (method in listOf("POST", "DELETE")) {
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
                val response = service.request(server.url("/orders").toString(), method,
                    mapOf("Authorization" to "private-unit"), if (method == "POST") "{}".toRequestBody() else null)
                assertEquals(503, response.code())
                assertEquals("0", response.headers()["Retry-After"])
                response.errorBody()!!.close()
                assertEquals(method, server.takeRequest(1, TimeUnit.SECONDS)!!.method)
                assertNull(server.takeRequest(30, TimeUnit.MILLISECONDS))
            }
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/stolen")))
            val redirect = service.get(server.url("/private").toString(), mapOf("Authorization" to "private-unit"))
            assertEquals(307, redirect.code()); redirect.errorBody()?.close()
            assertNull(other.takeRequest(30, TimeUnit.MILLISECONDS))
        } }
    }

    @Test fun eagerRetrofitErrorBufferIsBoundedAndCoroutineCancellationStopsPendingCall() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val service = httpService(server.url("/").toString(), OkHttpClient.Builder().singleExchange()
                .addInterceptor(HttpBodyLimit(128)).build())
            server.enqueue(MockResponse().setResponseCode(400).setBody("x".repeat(129)))
            assertTrue(runCatching { service.get(server.url("/").toString(), emptyMap()) }.exceptionOrNull() is HttpResponseTooLarge)
            server.takeRequest(1, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setBody("{}"))
            val job = launch { service.get(server.url("/cancel").toString(), emptyMap()).body()?.close() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(2, server.requestCount)
        }
    }
}
