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
    @Test fun sandboxCancellationUsesDeleteSignatureAndReportsBrokerErrors() = runBlocking {
        val reports = mutableListOf<BrokerResponse>()
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                .body("""{"code":"ORDER_FILLED","message":"Already filled","token":"PRIVATE-TOKEN"}""".toResponseBody()).build()
        }.build()
        val api = DnseTradingApi("PRIVATE-KEY", "PRIVATE-SECRET", false, client, reports::add)
        try { api.cancel("account1", "ORDER-1", "PRIVATE-TOKEN"); fail() }
        catch (e: TradeHttpFailure) { assertEquals(400, e.status) }
        val request = captured!!
        assertEquals("sb-openapi.dnse.com.vn", request.url.host)
        assertEquals("DELETE", request.method)
        assertEquals("/accounts/account1/orders/ORDER-1", request.url.encodedPath)
        assertEquals("NORMAL", request.url.queryParameter("orderCategory"))
        assertEquals("PRIVATE-TOKEN", request.header("trading-token"))
        assertNull(request.body)
        val nonce = Regex("nonce=\"([a-f0-9]{32})\"").find(request.header("X-Signature")!!)!!.groupValues[1]
        assertEquals(DnseSigning.signature("PRIVATE-KEY", "PRIVATE-SECRET", request.url.encodedPath,
            request.header("Date")!!, nonce, "delete"), request.header("X-Signature"))
        assertEquals(400, reports.single().status)
        assertTrue(reports.single().body.contains("ORDER_FILLED"))
        assertFalse(reports.single().body.contains("PRIVATE-TOKEN"))
    }
    @Test fun malformedCredentialsAreRejectedWithoutEchoingTheirValues() {
        for (bad in listOf("PRIVATE-KEY\nAPI secret", "PRIVATE SECRET", "PRIVATE\rKEY")) {
            try { DnseTradingApi(bad, "secret", false); fail() }
            catch (e: IllegalArgumentException) {
                assertEquals("Invalid DNSE credential format", e.message)
                assertFalse(e.message!!.contains("PRIVATE"))
            }
        }
    }
    @Test fun diagnosticsCannotBeEnabledForProduction() {
        try { DnseTradingApi("key", "secret", true, diagnostic = {}); fail() }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun sandboxCanPlaceAndImmediatelyCancelWithTheSameToken() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val body = when {
                chain.request().url.encodedPath == "/registration/trading-token" ->
                    """{"trading-token":"SANDBOX-TOKEN"}"""
                chain.request().method == "POST" -> """{"id":"594"}"""
                else -> """{"status":"pendingCancel"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val api = DnseTradingApi("key", "secret", false, client)
        val token = api.token("email_otp", "666666")
        val order = api.place("9379529478", draft, token)
        api.cancel("9379529478", order.getString("id"), token)
        assertEquals(listOf("POST", "POST", "DELETE"), requests.map { it.method })
        assertEquals("SANDBOX-TOKEN", requests[1].header("trading-token"))
        assertEquals("SANDBOX-TOKEN", requests[2].header("trading-token"))
        assertEquals("/accounts/9379529478/orders/594", requests[2].url.encodedPath)
    }
    @Test fun sandboxOtpAndResponseRedactionPreserveUsefulFields() = runBlocking {
        val reports = mutableListOf<BrokerResponse>()
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"trading-token":"PRIVATE-TOKEN"}""".toResponseBody()).build()
        }.build()
        assertEquals("PRIVATE-TOKEN", DnseTradingApi("key", "secret", false, client, reports::add).token("email_otp", "666666"))
        val buffer = okio.Buffer(); captured!!.body!!.writeTo(buffer)
        assertEquals("666666", JSONObject(buffer.readUtf8()).getString("passcode"))
        assertFalse(reports.single().body.contains("PRIVATE-TOKEN"))
        val clean = BrokerResponseRedaction.body(
            """{"code":"OA-100","data":[{"apiKey":"hidden","secret":"hidden","passcode":"666666","symbol":"HPG"}],"message":"Echo PRIVATE-KEY"}""",
            listOf("PRIVATE-KEY", "666666"))
        assertTrue(clean.contains("OA-100")); assertTrue(clean.contains("HPG"))
        assertFalse(clean.contains("hidden")); assertFalse(clean.contains("PRIVATE-KEY")); assertFalse(clean.contains("666666"))
        assertEquals("[Non-JSON response omitted]", BrokerResponseRedaction.body("HTML echo secret", emptyList()))
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
