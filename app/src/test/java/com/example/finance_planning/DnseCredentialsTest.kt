package com.example.finance_planning

import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Only synthetic broker responses. DNS is forbidden even if a fixture accidentally falls through. */
class DnseCredentialsTest {
    private fun slot(requests: MutableList<Request>, logs: MutableList<String> = mutableListOf()): DnseSlot {
        val client = OkHttpClient.Builder()
            .dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = error("No real broker permitted") })
            .addInterceptor {
                requests += it.request()
                Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("synthetic").body((if (it.request().method == "GET") "[]" else "{\"id\":\"synthetic-order\"}").toResponseBody()).build()
            }.build()
        return DnseSlot(DnseConfiguration(false), client, logger = logs::add)
    }
    @Test fun sharedClientUsesEachCallsDnseKeysAndTradingTokenWithoutLeakingToNextRead() = runBlocking {
        val requests = mutableListOf<Request>()
        val logs = mutableListOf<String>()
        val slot = slot(requests, logs)
        val first = DnseApi("SYNTHETIC-KEY-A", "SYNTHETIC-SECRET-A", false, slot)
        val second = DnseTradingApi("SYNTHETIC-KEY-B", "SYNTHETIC-SECRET-B", false, sharedSlot = slot)
        first.accounts()
        second.place("account-b", TradeDraft("HPG", "NB", 100, 25950, 5757), "SYNTHETIC-TOKEN-B")
        first.accounts()
        second.cancel("account-b", "synthetic-order", "SYNTHETIC-TOKEN-C")
        assertEquals(listOf("SYNTHETIC-KEY-A", "SYNTHETIC-KEY-B", "SYNTHETIC-KEY-A", "SYNTHETIC-KEY-B"),
            requests.map { it.header("X-Api-Key") })
        assertEquals(listOf(null, "SYNTHETIC-TOKEN-B", null, "SYNTHETIC-TOKEN-C"), requests.map { it.header("trading-token") })
        val nonces = requests.map { request ->
            assertEquals("2026-07-23", request.header("version"))
            assertEquals("application/json", request.header("Accept"))
            for (name in listOf("Authorization", "X-Firebase-AppCheck", "X-Planning-Authorization")) assertNull(request.header(name))
            val key = request.header("X-Api-Key")!!
            val secret = if (key.endsWith("A")) "SYNTHETIC-SECRET-A" else "SYNTHETIC-SECRET-B"
            val nonce = Regex("nonce=\"([a-f0-9]{32})\"").find(request.header("X-Signature")!!)!!.groupValues[1]
            assertEquals(DnseSigning.signature(key, secret, request.url.encodedPath, request.header("Date")!!,
                nonce, request.method.lowercase(java.util.Locale.US)), request.header("X-Signature"))
            nonce
        }
        assertEquals(4, nonces.distinct().size)
        assertFalse(logs.any { "SYNTHETIC" in it })
        assertFalse(requests[1].tag(DnseCredentials::class.java).toString().contains("SYNTHETIC"))
    }

    @Test fun wrongEnvironmentCredentialsAreRejectedBeforeAnyBrokerDispatch() = runBlocking {
        val requests = mutableListOf<Request>()
        val slot = slot(requests)
        val error = runCatching {
            slot.read("/accounts", emptyMap(), DnseCredentials("SYNTHETIC-KEY", "SYNTHETIC-SECRET", true))
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(requests.isEmpty())
    }

    @Test fun attachmentRemovesBackendAndStaleTradingHeadersAndDoesNotTransmitSecret() {
        val request = Request.Builder().url("https://sb-openapi.dnse.com.vn/accounts/a/orders?marketType=STOCK")
            .header("Authorization", "SYNTHETIC-BACKEND").header("X-Firebase-AppCheck", "SYNTHETIC-CHECK")
            .header("X-Planning-Authorization", "SYNTHETIC-QA").header("trading-token", "SYNTHETIC-OLD")
            .header("X-Api-Key", "SYNTHETIC-OLD-KEY").header("X-Signature", "SYNTHETIC-OLD-SIGNATURE").build()
        val signed = DnseCredentials("SYNTHETIC-NEW-KEY", "SYNTHETIC-SECRET", false).attach(request)
        for (name in listOf("Authorization", "X-Firebase-AppCheck", "X-Planning-Authorization", "trading-token"))
            assertNull(signed.header(name))
        assertEquals("SYNTHETIC-NEW-KEY", signed.header("X-Api-Key"))
        assertFalse(signed.headers.toString().contains("SYNTHETIC-SECRET"))
        assertEquals(request.url, signed.url)
        assertEquals(request.method, signed.method)
    }
}
