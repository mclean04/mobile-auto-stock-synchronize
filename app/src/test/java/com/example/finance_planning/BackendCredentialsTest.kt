package com.example.finance_planning

import com.example.finance_planning.network.*
import com.example.finance_planning.network.session.*
import com.example.finance_planning.core.AppFailure
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.EOFException

class BackendCredentialsTest {
    @Before fun resources() { TestText.install() }
    private fun client(block: (Request) -> String) = OkHttpClient.Builder()
        .dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = error("No network permitted") })
        .addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("synthetic").body(block(request).toResponseBody()).build()
        }.build()

    @Test fun everyCallObtainsANewSnapshotAndHealthOmitsCredentials() = runBlocking {
        val requests = mutableListOf<Request>()
        var token = "SYNTHETIC-1"
        var captures = 0
        val api = BackendApi(Transport(client { requests += it; "{}" }), {
            captures++; mapOf("Authorization" to "Bearer $token", "X-Firebase-AppCheck" to "SYNTHETIC-CHECK")
        }, requestContext = { _, _ -> SessionRequestContext { true } })
        api.syncStatus(); token = "SYNTHETIC-2"; api.syncStatus(); api.health()
        assertEquals(2, captures)
        assertEquals(listOf("Bearer SYNTHETIC-1", "Bearer SYNTHETIC-2", null), requests.map { it.header("Authorization") })
        assertNull(requests.last().header("X-Firebase-AppCheck"))
        assertTrue(requests.all { it.header("X-Planning-Authorization") == null })
        assertFalse(requests[0].tag(BackendCredentials::class.java).toString().contains("SYNTHETIC"))
    }

    @Test fun ownerChangeDuringSdkCapturePreventsDispatch() = runBlocking {
        var owner = "first"
        var calls = 0
        val api = BackendApi(Transport(client { calls++; "{}" }), {
            owner = "second"; mapOf("Authorization" to "Bearer SYNTHETIC")
        }, requestContext = { _, _ -> val original = owner; SessionRequestContext { owner == original } })
        assertTrue(runCatching { api.syncStatus() }.exceptionOrNull() is SupersededNetworkContext)
        assertEquals(0, calls)
    }

    @Test fun retiredReadIsRejectedButAcknowledgedMutationIsRetained() = runBlocking {
        for (mutation in listOf(false, true)) {
            var active = true
            var calls = 0
            val api = BackendApi(Transport(client { calls++; active = false; "{}" }), {
                mapOf("Authorization" to "Bearer SYNTHETIC")
            }, requestContext = { _, _ -> SessionRequestContext { active } })
            val result = runCatching { if (mutation) api.retryProjection() else api.syncStatus() }
            if (mutation) assertTrue(result.isSuccess) else assertTrue(result.exceptionOrNull() is SupersededNetworkContext)
            assertEquals(1, calls)
        }
    }

    @Test fun snapshotIsImmutableAndRechecksContextAtAttachment() {
        var active = true
        val headers = mutableMapOf("Authorization" to "Bearer SYNTHETIC-1")
        val snapshot = BackendCredentials(headers) { if (!active) throw SupersededNetworkContext() }
        headers["Authorization"] = "Bearer SYNTHETIC-2"
        val request = Request.Builder().url("https://example.invalid/").build()
        assertEquals("Bearer SYNTHETIC-1", snapshot.attach(request).header("Authorization"))
        active = false
        assertTrue(runCatching { snapshot.attach(request) }.exceptionOrNull() is SupersededNetworkContext)
        assertTrue(runCatching { BackendCredentials(mapOf("X-Planning-Authorization" to "")) }.isFailure)
    }

    @Test fun transportRetainsOriginalIoCauseWithoutReplaying() = runBlocking {
        var calls = 0
        val original = EOFException("synthetic connection closed")
        val api = BackendApi(Transport(client { calls++; throw original }), { emptyMap() })
        val error = runCatching { api.retryProjection() }.exceptionOrNull()
        assertTrue(error is AppFailure)
        assertTrue(generateSequence<Throwable>(error!!) { it.cause }.any { it === original || it is EOFException })
        assertEquals(1, calls)
    }

    @Test fun sessionDecodeDistinguishesBodyIoFromInvalidJson() {
        val original = EOFException("synthetic incomplete body")
        val source = object : Source {
            override fun timeout() = Timeout.NONE
            override fun read(sink: Buffer, byteCount: Long): Long = throw original
            override fun close() {}
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        val read = runCatching { SessionGson.decode(body) }.exceptionOrNull() as SessionProtocolFailure
        assertEquals("body_read", read.stage); assertSame(original, read.cause)
        val parse = runCatching { SessionGson.decode("not-json".toResponseBody()) }.exceptionOrNull() as SessionProtocolFailure
        assertEquals("decode", parse.stage)
        assertEquals("context_changed", ApiDiagnostics.failure(SupersededNetworkContext()))
    }
}
