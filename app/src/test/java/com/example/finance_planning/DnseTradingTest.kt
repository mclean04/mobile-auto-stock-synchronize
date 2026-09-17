package com.example.finance_planning

import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DnseTradingTest {
    private val draft = TradeDraft("HPG", "NB", 100, 25950, 5757)
    @Test fun vndOrderContractDoesNotApplyReadPriceMultiplier() {
        val body = draft.body()
        assertEquals(25950L, body.getLong("price"))
        assertEquals(100, body.getInt("quantity"))
        assertEquals("LO", body.getString("orderType"))
        assertEquals("NB", body.getString("side"))
        assertEquals(5757, body.getInt("loanPackageId"))
    }
    @Test fun invalidLotsPricesSidesAndSymbolsAreRejected() {
        val invalid = listOf(draft.copy(quantity = 0), draft.copy(quantity = 101), draft.copy(price = 0),
            draft.copy(price = -1), draft.copy(side = "BUY"), draft.copy(symbol = "HPG/other"), draft.copy(packageId = 0))
        invalid.forEach { try { it.body(); fail("Invalid draft accepted: $it") } catch (_: IllegalArgumentException) {} }
        draft.copy(quantity = 99).body(); draft.copy(quantity = 200, side = "NS").body()
    }
    @Test fun postSignatureUsesPostTargetExcludingQuery() {
        val nonce = "0123456789abcdef0123456789abcdef"
        val date = "Fri, 18 Sep 2026 00:00:00 +0000"
        assertNotEquals(DnseSigning.signature("key", "secret", "/accounts/a/orders", date, nonce),
            DnseSigning.signature("key", "secret", "/accounts/a/orders", date, nonce, "post"))
        try { DnseSigning.signature("key", "secret", "/orders?marketType=STOCK", date, nonce, "post"); fail() }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun signedWritesUseCorrectEndpointsAndTradingHeaderWithoutLogging() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (chain.request().url.encodedPath == "/registration/trading-token")
                    """{"tradingToken":"PRIVATE-TOKEN"}""" else """{"id":"ORDER-1"}""").toResponseBody()).build()
        }.build()
        val api = DnseTradingApi("PRIVATE-KEY", "PRIVATE-SECRET", true, client)
        assertEquals("PRIVATE-TOKEN", api.token("smart_otp", "123456"))
        assertEquals("ORDER-1", api.place("account1", draft, "PRIVATE-TOKEN").getString("id"))
        assertEquals("POST", requests[0].method)
        assertEquals("/registration/trading-token", requests[0].url.encodedPath)
        assertNull(requests[0].header("trading-token"))
        val write = requests[1]
        assertEquals("/accounts/account1/orders", write.url.encodedPath)
        assertEquals("STOCK", write.url.queryParameter("marketType"))
        assertEquals("NORMAL", write.url.queryParameter("orderCategory"))
        assertEquals("PRIVATE-TOKEN", write.header("trading-token"))
        val date = write.header("Date")!!
        val signature = write.header("X-Signature")!!
        val nonce = Regex("nonce=\"([a-f0-9]{32})\"").find(signature)!!.groupValues[1]
        assertEquals(DnseSigning.signature("PRIVATE-KEY", "PRIVATE-SECRET", write.url.encodedPath, date, nonce, "post"), signature)
        val buffer = okio.Buffer(); write.body!!.writeTo(buffer)
        assertEquals(25950, JSONObject(buffer.readUtf8()).getInt("price"))
        val defaults = DnseTradingApi("key", "secret", false).client
        assertFalse(defaults.retryOnConnectionFailure); assertFalse(defaults.followRedirects)
        assertFalse(defaults.followSslRedirects); assertTrue(defaults.interceptors.isEmpty())
    }
    @Test fun transportFailureNeverAutomaticallyResubmits() = runBlocking {
        var attempts = 0
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor {
            attempts++; throw IOException("simulated uncertain submission")
        }.build()
        try { DnseTradingApi("key", "secret", false, client).place("a", draft, "token"); fail() }
        catch (_: IOException) {}
        assertEquals(1, attempts)
    }
}
