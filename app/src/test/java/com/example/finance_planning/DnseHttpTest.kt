package com.example.finance_planning

import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

class DnseHttpTest {
    @Test fun retrofitBodyLoggingPreservesBodyAndNeverLeaksCredentials() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val logs = mutableListOf<String>()
            val raw = """{"accounts":[{"id":"PRIVATE-ACCOUNT","token":"PRIVATE-TOKEN"}],"stock":{"availableCash":987654321},"message":"PRIVATE-SECRET"}"""
            server.enqueue(MockResponse().setBody(raw).addHeader("Content-Type", "application/json"))
            val client = OkHttpClient.Builder().addInterceptor(SafeBodyLoggingInterceptor(logs::add)).build()
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
                assertFalse(printed.contains(secret))
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
                .client(OkHttpClient.Builder().addInterceptor(SafeBodyLoggingInterceptor(logs::add)).build())
                .build().create(DnseReadService::class.java)
            val response = service.get(server.url("/accounts").toString(), emptyMap())
            assertEquals(401, response.code())
            assertEquals(raw, response.errorBody()!!.use { it.string() })
            assertTrue(logs.any { "DNSE-error-code: OA-401" in it })
            assertFalse(logs.any { "PRIVATE-SECRET" in it })
        } finally { server.shutdown() }
    }
    @Test fun malformedDeepLargeAndUnknownBodiesAreRedacted() {
        assertFalse(SafeJsonBody.render("<html>PRIVATE-SECRET</html>").contains("PRIVATE-SECRET"))
        assertFalse(SafeJsonBody.render("""{"PRIVATE-KEY":"value","id":"PRIVATE-ID"}""").contains("PRIVATE"))
        assertTrue(SafeJsonBody.render("[".repeat(50) + "0" + "]".repeat(50)).contains("deep"))
        assertTrue(SafeJsonBody.render("x".repeat(9000)).contains("large"))
    }
}
