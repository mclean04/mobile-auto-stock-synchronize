package com.example.finance_planning

import com.example.finance_planning.core.*
import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocalBackendConfigurationTest {
    @Before fun text() { TestText.install() }

    @Test fun selectedOriginPreservesCallerAuthAndNeverFallsBack() = runBlocking {
        val requests = mutableListOf<Request>()
        val transport = Transport(OkHttpClient.Builder().addInterceptor {
            requests += it.request()
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body("{}".toResponseBody()).build()
        }.build())
        if (BuildConfig.LOCAL_BACKEND) {
            assertTrue(BuildConfig.DEBUG)
            assertEquals("com.example.finance_planning", BuildConfig.APPLICATION_ID)
            assertEquals("http://127.0.0.1:8080", Contracts.BACKEND)
            val callerHeaders = mapOf("Authorization" to "Bearer component-test-only",
                "X-Firebase-AppCheck" to "component-test-app-check")
            assertEquals("{}", transport.request(Contracts.BACKEND + "/v1/sync/status",
                headers = callerHeaders))
            assertEquals(callerHeaders["Authorization"], requests.single().header("Authorization"))
            assertEquals(callerHeaders["X-Firebase-AppCheck"], requests.single().header("X-Firebase-AppCheck"))
            for (url in listOf("http://127.0.0.1:18766/v1/sync/status",
                "http://127.0.0.1:18767/v1/sync/status",
                "http://localhost:8080/v1/sync/status", "http://127.0.0.1:8080@outside.invalid/",
                "http://127.0.0.1:8080/#fragment",
                "https://planning-backend-1026748304024.asia-southeast1.run.app/v1/sync/status")) {
                assertTrue(runCatching { transport.request(url, headers = callerHeaders) }.isFailure)
            }
            assertEquals(1, requests.size)
        } else {
            assertEquals("https://planning-backend-1026748304024.asia-southeast1.run.app", Contracts.BACKEND)
            assertFalse(LocalBackend.active)
            assertTrue(runCatching { transport.request("http://127.0.0.1:8080/health") }.isFailure)
            assertTrue(requests.isEmpty())
        }
    }

}
