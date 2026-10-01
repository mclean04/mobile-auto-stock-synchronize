package com.example.finance_planning

import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

/** Dynamic URL is confined to this local logging fixture; product endpoints are fixed. */
private interface DnseReadService {
    @retrofit2.http.Streaming @retrofit2.http.GET
    suspend fun get(@retrofit2.http.Url url: String,
                    @retrofit2.http.HeaderMap headers: Map<String, String>): retrofit2.Response<okhttp3.ResponseBody>
}

class DnseHttpTest {
    @org.junit.Before fun initializeTextResources() { TestText.install() }
    @Test fun debugBodyLoggingPreservesRawBodyHeadersAndUrl() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val logs = mutableListOf<String>()
            val raw = """{"accounts":[{"id":"PRIVATE-ACCOUNT","token":"PRIVATE-TOKEN"}],"stock":{"availableCash":987654321},"message":"PRIVATE-SECRET"}"""
            server.enqueue(MockResponse().setBody(raw).addHeader("Content-Type", "application/json"))
            val client = OkHttpClient.Builder().addInterceptor(DebugBodyLoggingInterceptor(logs::add)).build()
            val service = Retrofit.Builder().baseUrl(server.url("/")).client(client).build()
                .create(DnseReadService::class.java)
            val result = service.get(server.url("/accounts?api_key=PRIVATE-KEY").toString(),
                mapOf("X-Api-Key" to "PRIVATE-KEY", "X-Signature" to "PRIVATE-SIGNATURE"))
            assertEquals(raw, result.body()!!.use { it.string() })
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("PRIVATE-KEY", request.getHeader("X-Api-Key"))
            assertEquals("PRIVATE-SIGNATURE", request.getHeader("X-Signature"))
            val printed = logs.joinToString("\n")
            for (secret in listOf("PRIVATE-ACCOUNT", "PRIVATE-TOKEN", "PRIVATE-SECRET", "PRIVATE-KEY", "PRIVATE-SIGNATURE", "987654321"))
                assertTrue(printed.contains(secret))
            assertTrue(logs.first().startsWith("--> GET "))
            assertTrue(logs.any { it.startsWith("<-- 200 ") })
            assertTrue(logs.last().startsWith("<-- END HTTP"))
            assertTrue(printed.contains("availableCash"))
        } finally { server.shutdown() }
    }
    @Test fun interceptorRetainsErrorBodyAndSafeErrorCode() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val logs = mutableListOf<String>()
            val raw = """{"code":"OA-401","message":"PRIVATE-SECRET"}"""
            server.enqueue(MockResponse().setResponseCode(401).setBody(raw))
            val service = Retrofit.Builder().baseUrl(server.url("/"))
                .client(OkHttpClient.Builder().addInterceptor(DebugBodyLoggingInterceptor(logs::add)).build())
                .build().create(DnseReadService::class.java)
            val response = service.get(server.url("/accounts").toString(), emptyMap())
            assertEquals(401, response.code())
            assertEquals(raw, response.errorBody()!!.use { it.string() })
            assertTrue(logs.any { "DNSE-error-code: OA-401" in it })
            assertTrue(logs.any { "PRIVATE-SECRET" in it })
        } finally { server.shutdown() }
    }
    @Test fun disabledInterceptorEmitsNothing() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val logs = mutableListOf<String>()
            server.enqueue(MockResponse().setBody("{}"))
            val client = OkHttpClient.Builder().addInterceptor(DebugBodyLoggingInterceptor(logs::add, false)).build()
            val service = Retrofit.Builder().baseUrl(server.url("/")).client(client).build()
                .create(DnseReadService::class.java)
            assertEquals("{}", service.get(server.url("/").toString(), emptyMap()).body()!!.use { it.string() })
            assertTrue(logs.isEmpty())
        } finally { server.shutdown() }
    }
}
