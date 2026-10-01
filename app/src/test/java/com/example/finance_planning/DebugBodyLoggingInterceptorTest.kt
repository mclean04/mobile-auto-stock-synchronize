package com.example.finance_planning

import com.example.finance_planning.core.Contracts
import com.example.finance_planning.network.DebugBodyLoggingInterceptor
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DebugBodyLoggingInterceptorTest {
    private fun reply(request: Request, code: Int, body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("PRIVATE-STATUS-MESSAGE")
        .header("Set-Cookie", "PRIVATE-COOKIE").body(body).build()

    @Test fun registration409LogsOnlyMetadataAndFixedCodeWhilePreservingOneShotRequest() {
        val logs = mutableListOf<String>()
        val requestJson = """{"fcm_token":"PRIVATE-FCM","platform":"android"}"""
        var writes = 0
        val body = object : RequestBody() {
            override fun contentType() = null
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { writes++; sink.writeUtf8(requestJson) }
        }
        val raw = """{"detail":"device_limit_10","echo":"PRIVATE-RESPONSE","fcm_token":"PRIVATE-FCM"}"""
        val client = OkHttpClient.Builder().addInterceptor(DebugBodyLoggingInterceptor(logs::add))
            .addInterceptor {
                val request = it.request()
                assertEquals(0, writes) // The logger never serializes even once.
                assertEquals("Bearer PRIVATE-AUTH", request.header("Authorization"))
                assertEquals("PRIVATE-ATTESTATION", request.header("X-Firebase-AppCheck"))
                assertEquals("token=PRIVATE-QUERY", request.url.encodedQuery)
                val sent = Buffer(); request.body!!.writeTo(sent)
                assertEquals(requestJson, sent.readUtf8())
                reply(request, 409, raw.toResponseBody())
            }.build()
        val request = Request.Builder().url(Contracts.BACKEND + "/v1/devices/PRIVATE-DEVICE?token=PRIVATE-QUERY")
            .put(body).header("Authorization", "Bearer PRIVATE-AUTH")
            .header("X-Firebase-AppCheck", "PRIVATE-ATTESTATION").build()
        client.newCall(request).execute().use {
            assertEquals(409, it.code)
            assertEquals("PRIVATE-COOKIE", it.header("Set-Cookie"))
            assertEquals(raw, it.body!!.string())
        }
        assertEquals(1, writes)
        val printed = logs.joinToString("\n")
        assertFalse(printed.contains("PRIVATE"))
        assertFalse(printed.contains("fcm_token"))
        assertFalse(printed.contains("Authorization"))
        assertTrue(printed.contains("PUT planning-backend /v1/devices/{id}"))
        assertTrue(printed.contains("<-- 409"))
        assertTrue(printed.contains("error-code: device_limit_10"))
        assertTrue(Regex("\\([0-9]+ms\\)").containsMatchIn(printed))
    }

    @Test fun loggerDoesNotReadOrDrainAnUnconsumedOrPartiallyReadResponse() {
        val logs = mutableListOf<String>()
        var reads = 0; var closes = 0
        val bytes = Buffer().writeUtf8("""{"detail":"device_limit_10","secret":"PRIVATE"}""")
        val upstream = object : Source {
            override fun timeout() = Timeout.NONE
            override fun read(sink: Buffer, byteCount: Long): Long { reads++; return bytes.read(sink, byteCount) }
            override fun close() { closes++ }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = upstream
        }
        val client = OkHttpClient.Builder().addInterceptor(DebugBodyLoggingInterceptor(logs::add))
            .addInterceptor { reply(it.request(), 409, body) }.build()
        client.newCall(Request.Builder().url(Contracts.BACKEND + "/v1/devices/PRIVATE").build()).execute().use {
            assertEquals(0, reads)
            assertEquals('{'.code.toByte(), it.body!!.source().readByte())
            assertEquals(1, reads)
        }
        assertEquals(1, closes)
        assertFalse(logs.any { "error-code" in it || "PRIVATE" in it })
    }

    @Test fun transportAndBodyExceptionsAreRethrownWithoutTheirMessages() {
        for (bodyFailure in listOf(false, true)) for (failure in listOf(
            IOException("PRIVATE-AUTH PRIVATE-FCM PRIVATE-URL"),
            IllegalStateException("PRIVATE-AUTH PRIVATE-FCM PRIVATE-URL"))) {
            val logs = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor(DebugBodyLoggingInterceptor(logs::add))
                .addInterceptor {
                    if (!bodyFailure) throw failure
                    val source = object : Source {
                        override fun timeout() = Timeout.NONE
                        override fun read(sink: Buffer, byteCount: Long): Long = throw failure
                        override fun close() {}
                    }.buffer()
                    reply(it.request(), 409, object : ResponseBody() {
                        override fun contentType() = null
                        override fun contentLength() = -1L
                        override fun source() = source
                    })
                }.build()
            val caught = runCatching {
                client.newCall(Request.Builder().url(Contracts.BACKEND + "/v1/devices/PRIVATE?secret=PRIVATE").build())
                    .execute().use { it.body!!.string() }
            }.exceptionOrNull()
            assertSame(failure, caught)
            assertTrue(logs.any { (if (failure is IOException) "network_io" else "local_error") in it })
            assertFalse(logs.any { "PRIVATE" in it })
        }
    }

    @Test fun closeFailureIsPreservedWithoutItsMessage() {
        val logs = mutableListOf<String>()
        val failure = IllegalStateException("PRIVATE-CLOSE")
        val source = object : Source {
            override fun timeout() = Timeout.NONE
            override fun read(sink: Buffer, byteCount: Long) = -1L
            override fun close(): Unit = throw failure
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        val client = OkHttpClient.Builder().addInterceptor(DebugBodyLoggingInterceptor(logs::add))
            .addInterceptor { reply(it.request(), 409, body) }.build()
        val response = client.newCall(Request.Builder().url(Contracts.BACKEND + "/v1/devices/PRIVATE").build()).execute()
        assertSame(failure, runCatching { response.body!!.source().close() }.exceptionOrNull())
        assertTrue(logs.any { "BODY CLOSE FAILED: local_error" in it })
        assertFalse(logs.any { "PRIVATE" in it })
    }

    @Test fun unknownMalformedAndOversizedErrorsAreOmittedAndTypedKnownCodeIsSafe() {
        val cases = listOf(
            """{"detail":"PRIVATE-CODE"}""" to null,
            "<html>PRIVATE-HTML</html>" to null,
            ("""{"detail":"device_limit_10","echo":"""" + "PRIVATE".repeat(800) + "\"}") to null,
            """{"error":{"code":"device_limit_10","message":"PRIVATE"}}""" to "device_limit_10"
        )
        for ((raw, expectedCode) in cases) {
            val logs = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor(DebugBodyLoggingInterceptor(logs::add))
                .addInterceptor { reply(it.request(), 409, raw.toResponseBody()) }.build()
            client.newCall(Request.Builder().url(Contracts.BACKEND + "/PRIVATE?PRIVATE").build())
                .execute().use { assertEquals(raw, it.body!!.string()) }
            assertFalse(logs.any { "PRIVATE" in it })
            assertEquals(expectedCode != null, logs.any { "error-code:" in it })
        }
    }

    @Test fun failingLogSinkCannotBreakSuccessOrReplaceTransportFailure() {
        val failure = IOException("PRIVATE")
        for (fails in listOf(false, true)) {
            val client = OkHttpClient.Builder()
                .addInterceptor(DebugBodyLoggingInterceptor({ throw IllegalStateException("PRIVATE-LOGGER") }))
                .addInterceptor {
                    if (fails) throw failure
                    reply(it.request(), 200, "PRIVATE-BODY".toResponseBody())
                }.build()
            val result = runCatching {
                client.newCall(Request.Builder().url(Contracts.BACKEND + "/health").build())
                    .execute().use { it.body!!.string() }
            }
            if (fails) assertSame(failure, result.exceptionOrNull()) else assertEquals("PRIVATE-BODY", result.getOrThrow())
        }
    }
}
