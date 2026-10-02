package com.example.finance_planning

import com.example.finance_planning.core.Contracts
import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** All credentials and payloads are synthetic. No broker, device or provider is contacted. */
class BackendFullLogcatTest {
    private fun response(request: Request, raw: String, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .header("Set-Cookie", "SYNTHETIC-COOKIE").body(raw.toResponseBody()).build()
    private val request = Request.Builder().url(Contracts.BACKEND + "/v1/sync/status")
        .header("Authorization", "Bearer SYNTHETIC-AUTH")
        .header("X-Firebase-AppCheck", "SYNTHETIC-CHECK").build()

    @Test fun fullHeadersAndBodiesPreserveOneShotBytesAndUnicode() {
        val logs = mutableListOf<String>()
        val raw = "{\"text\":\"" + "😀Tiếng Việt".repeat(400) + "\"}"
        var writes = 0
        val body = object : RequestBody() {
            override fun contentType() = null
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { writes++; sink.writeUtf8(raw) }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugBodyLoggingInterceptor(logs::add, fullBodies = true))
            .addInterceptor {
                assertEquals(0, writes)
                assertTrue(it.request().body!!.isOneShot())
                assertFalse(it.request().body!!.isDuplex())
                val sent = Buffer(); it.request().body!!.writeTo(sent)
                assertEquals(raw, sent.readUtf8())
                response(it.request(), raw, 409)
            }.build()
        client.newCall(request.newBuilder().put(body).build()).execute().use {
            assertEquals(409, it.code)
            assertEquals(raw, it.body!!.string())
        }
        assertEquals(1, writes)
        if (!BuildConfig.DEBUG) { assertTrue(logs.isEmpty()); return }
        assertTrue(logs.contains("--> Authorization: Bearer SYNTHETIC-AUTH"))
        assertTrue(logs.contains("--> X-Firebase-AppCheck: SYNTHETIC-CHECK"))
        assertTrue(logs.contains("<-- Set-Cookie: SYNTHETIC-COOKIE"))
        for (prefix in listOf("--> BODY ", "<-- BODY ")) {
            assertEquals(raw, logs.filter { it.startsWith(prefix) && !it.startsWith(prefix + "[body") }
                .joinToString("") { it.removePrefix(prefix) })
        }
        assertTrue(logs.all { it.toByteArray(Charsets.UTF_8).size < 4000 })
    }

    @Test fun unconsumedResponseIsNeverDrainedAndPartialCloseIsLabelled() {
        val logs = mutableListOf<String>()
        var reads = 0
        val source = object : Source {
            override fun timeout() = Timeout.NONE
            override fun read(sink: Buffer, byteCount: Long): Long { reads++; return -1 }
            override fun close() {}
        }.buffer()
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugBodyLoggingInterceptor(logs::add, fullBodies = true))
            .addInterceptor { response(it.request(), "").newBuilder().body(object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun source() = source
            }).build() }.build()
        client.newCall(request).execute().use { assertEquals(0, reads) }
        assertEquals(0, reads)
        if (BuildConfig.DEBUG) assertTrue(logs.any { "partial/not consumed" in it })
        else assertTrue(logs.isEmpty())
    }

    @Test fun disabledLoggerDoesNotObserveHeadersOrBodies() {
        val logs = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugBodyLoggingInterceptor(logs::add, enabled = false, fullBodies = true))
            .addInterceptor { response(it.request(), "SYNTHETIC-BODY") }.build()
        client.newCall(request).execute().use { assertEquals("SYNTHETIC-BODY", it.body!!.string()) }
        assertTrue(logs.isEmpty())
    }

    @Test fun diagnosticSinkFailureCannotChangeResultOrOriginalFailure() {
        val failure = IOException("SYNTHETIC-failure")
        for (fails in listOf(false, true)) {
            var attempts = 0
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .addInterceptor(DebugBodyLoggingInterceptor({ error("synthetic log sink failure") }, fullBodies = true))
                .addInterceptor {
                    attempts++
                    if (fails) throw failure
                    response(it.request(), "SYNTHETIC-BODY")
                }.build()
            val result = runCatching { client.newCall(request).execute().use { it.body!!.string() } }
            if (fails) assertSame(failure, result.exceptionOrNull())
            else assertEquals("SYNTHETIC-BODY", result.getOrThrow())
            assertEquals(1, attempts)
        }
    }

    @Test fun responseCaptureIsBoundedWithoutChangingConsumerBytes() {
        val logs = mutableListOf<String>()
        val raw = "x".repeat(4 * 1024 * 1024 + 1)
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugBodyLoggingInterceptor(logs::add, fullBodies = true))
            .addInterceptor { response(it.request(), raw) }.build()
        client.newCall(request).execute().use { assertEquals(raw, it.body!!.string()) }
        if (BuildConfig.DEBUG) {
            assertTrue(logs.any { "truncated at 4194304 bytes" in it })
            assertEquals(4 * 1024 * 1024, logs.filter { it.startsWith("<-- BODY x") }
                .sumOf { it.removePrefix("<-- BODY ").length })
        } else assertTrue(logs.isEmpty())
    }

    @Test fun sharedBackendUsesFullLoggingOnlyInDebugBuilds() = runBlocking {
        val logs = mutableListOf<String>()
        val seed = OkHttpClient.Builder().dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = error("No network permitted") })
            .addInterceptor { response(it.request(), "{\"status\":\"SYNTHETIC\"}") }.build()
        val slot = BackendSlot(BackendConfiguration(), seed, logs::add)
        val result = slot.legacy.get(Contracts.BACKEND + "/health",
            mapOf("Authorization" to "Bearer SYNTHETIC-AUTH"))
        result.body()!!.use { assertEquals("{\"status\":\"SYNTHETIC\"}", it.string()) }
        if (BuildConfig.DEBUG) {
            assertTrue(logs.any { "Bearer SYNTHETIC-AUTH" in it })
            assertTrue(logs.any { "SYNTHETIC" in it && "BODY" in it })
        } else assertTrue(logs.isEmpty())
    }
}
