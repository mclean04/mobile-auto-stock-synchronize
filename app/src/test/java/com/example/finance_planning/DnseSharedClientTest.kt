package com.example.finance_planning

import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** Component checks terminate at an in-memory broker; no broker socket is opened. */
class DnseSharedClientTest {
    private val draft = TradeDraft("HPG", "NB", 100, 25950, 5757)
    private val noDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> = error("Fake broker must never reach DNS")
    }
    @Before fun textResources() { TestText.install() }
    private fun slot(logs: MutableList<String> = mutableListOf(),
                     reply: (Request) -> Pair<Int, String>): DnseSlot {
        val fake = OkHttpClient.Builder().dns(noDns)
            .addInterceptor { chain ->
                val (code, body) = reply(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fake").body(body.toResponseBody()).build()
            }.build()
        return DnseSlot(DnseConfiguration(false), fake, fakeOnly = true, logger = logs::add)
    }
    private fun trade(slot: DnseSlot, check: () -> Unit = {}) =
        DnseTradingApi("key", "secret", false, checkContext = check, sharedSlot = slot)

    @Test fun normalReadAndTradeReuseOneInstanceAndEnvironmentReplacementRetiresOldInstance() {
        NetworkClients.application.invalidateDnse()
        val read = DnseApi("key", "secret", false)
        val trade = DnseTradingApi("key", "secret", false)
        assertSame(read.slot, trade.slot)
        assertSame(read.slot.retrofit, trade.slot.retrofit)
        assertEquals(20_000, trade.client.connectTimeoutMillis)
        assertEquals(40_000, trade.client.readTimeoutMillis)
        assertEquals(60_000, trade.client.callTimeoutMillis)
        val production = NetworkClients.application.dnse(DnseConfiguration(true))
        assertNotSame(read.slot.retrofit, production.retrofit)
        assertTrue(runCatching { read.slot.checkActive() }.exceptionOrNull() is SupersededNetworkContext)
        assertTrue(runCatching { DnseApi("key", "secret", false, production) }.isFailure)
        assertTrue(runCatching { DnseTradingApi("key", "secret", false, sharedSlot = production) }.isFailure)
        NetworkClients.application.invalidateDnse()
        assertTrue(runCatching { production.checkActive() }.exceptionOrNull() is SupersededNetworkContext)
    }

    @Test fun allFixedEndpointsKeepSignaturesQueriesAndBodiesOnSharedTransport() = runBlocking {
        val requests = mutableListOf<Request>()
        val slot = slot { request ->
            requests += request
            200 to when (request.url.encodedPath) {
                "/registration/trading-token" -> """{"tradingToken":"fake-token"}"""
                "/accounts" -> "[]"
                "/accounts/a/loan-packages" -> "[]"
                "/accounts/a/orders/history", "/accounts/a/orders" ->
                    if (request.method == "POST") """{"id":"o"}""" else "[]"
                else -> "{}"
            }
        }
        val read = DnseApi("key", "secret", false, slot)
        val trade = trade(slot)
        read.accounts(); read.balances("a"); read.positions("a")
        read.history("a", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))
        read.today("a", "NORMAL"); read.detail("a", "o"); read.executions("a", "o")
        trade.accounts(); trade.balances("a"); trade.packages("a", "HPG"); trade.ppse("a", draft)
        trade.orders("a"); trade.order("a", "o"); trade.emailOtp()
        assertEquals("fake-token", trade.token("email_otp", "123456"))
        trade.place("a", draft, "fake-token"); trade.cancel("a", "o", "fake-token")
        assertEquals(17, requests.size)
        requests.forEach { request ->
            assertEquals("sb-openapi.dnse.com.vn", request.url.host)
            val signature = request.header("X-Signature")!!
            val nonce = Regex("nonce=\"([a-f0-9]{32})\"").find(signature)!!.groupValues[1]
            assertEquals(DnseSigning.signature("key", "secret", request.url.encodedPath,
                request.header("Date")!!, nonce, request.method.lowercase()), signature)
        }
        assertEquals("marketType=STOCK&from=2026-09-01&to=2026-09-30&pageIndex=0&pageSize=100", requests[3].url.encodedQuery)
        assertEquals("marketType=STOCK&symbol=HPG&price=25950&loanPackageId=5757", requests[10].url.encodedQuery)
        assertEquals("fake-token", requests[15].header("trading-token"))
        val body = okio.Buffer().also { requests[15].body!!.writeTo(it) }.readUtf8()
        assertEquals(draft.body().toString(), body)
    }

    @Test fun tradingCallsCannotEnterRawReadLoggerEvenForSameAccountsPath() = runBlocking {
        val logs = mutableListOf<String>()
        val slot = slot(logs) { 200 to "[]" }
        DnseApi("key", "secret", false, slot).accounts()
        assertTrue(logs.any { it.startsWith("--> GET") })
        logs.clear()
        trade(slot).accounts()
        trade(slot).cancel("a", "o", "PRIVATE-TRADING-TOKEN")
        assertTrue(logs.isEmpty())
    }

    @Test fun dispatchRechecksContextAndConvertsSessionExceptionsWithoutBrokerCall() = runBlocking {
        var calls = 0
        val slot = slot { calls++; 200 to "{}" }
        var checks = 0
        val broker = trade(slot) {
            checks++
            if (checks == 3) throw IllegalStateException("session changed")
        }
        val failure = runCatching { broker.place("a", draft, "token") }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(3, checks)
        assertEquals(0, calls)
    }

    @Test fun accountOrCredentialChangePreventsOldWrapperFromSending() = runBlocking {
        var uid = "owner-a"
        var credential = "first"
        var calls = 0
        val slot = slot { calls++; 200 to "[]" }
        val broker = trade(slot).withRequestContext {
            if (uid != "owner-a" || credential != "first") throw SupersededNetworkContext()
        }
        broker.accounts()
        credential = "second"
        assertTrue(runCatching { broker.accounts() }.exceptionOrNull() is SupersededNetworkContext)
        credential = "first"; uid = "owner-b"
        assertTrue(runCatching { broker.place("a", draft, "token") }.exceptionOrNull() is SupersededNetworkContext)
        assertEquals(1, calls)
    }

    @Test fun changedContextDiscardsReadButRetainsAcknowledgedMutationForOwningJournal() = runBlocking {
        var current = true
        val slot = slot { current = false; 200 to """{"id":"confirmed-order"}""" }
        val broker = trade(slot) { if (!current) throw SupersededNetworkContext() }
        assertTrue(runCatching { broker.balances("a") }.exceptionOrNull() is SupersededNetworkContext)
        current = true
        assertEquals("confirmed-order", broker.place("a", draft, "token").getString("id"))
        assertFalse(current)
        current = true
        assertTrue(runCatching { DnseApi("key", "secret", false, slot) {
            if (!current) throw SupersededNetworkContext()
        }.balances("a") }.exceptionOrNull() is SupersededNetworkContext)
    }

    @Test fun responseLimitsRemainDifferentAndNonDiagnosticTradeErrorsAreOmitted() = runBlocking {
        var size = 65_536
        var status = 200
        val slot = slot { status to ("{}" + " ".repeat(size - 2)) }
        val broker = trade(slot)
        broker.balances("a")
        size++
        assertTrue(runCatching { broker.balances("a") }.exceptionOrNull() is IllegalArgumentException)
        DnseApi("key", "secret", false, slot).balances("a")
        size = 4 * 1024 * 1024
        DnseApi("key", "secret", false, slot).balances("a")
        size++
        assertTrue(runCatching { DnseApi("key", "secret", false, slot).balances("a") }.isFailure)
        status = 400
        assertEquals(400, (runCatching { broker.place("a", draft, "token") }.exceptionOrNull() as TradeHttpFailure).status)
        val diagnostic = DnseTradingApi("key", "secret", false, diagnostic = {}, sharedSlot = slot)
        assertTrue(runCatching { diagnostic.cancel("a", "o", "token") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun retryAfterAndRedirectResponsesNeverReplayTradingRequests() = runBlocking {
        for (status in listOf(307, 401, 408, 503)) {
            var calls = 0
            val seed = OkHttpClient.Builder().dns(noDns).addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("fake").header("Retry-After", "0")
                    .header("Location", "https://openapi.dnse.com.vn/accounts/a/orders")
                    .body("{}".toResponseBody()).build()
            }.build()
            val broker = trade(DnseSlot(DnseConfiguration(false), seed, fakeOnly = true))
            assertEquals(status, (runCatching { broker.place("a", draft, "token") }.exceptionOrNull() as TradeHttpFailure).status)
            assertEquals(1, calls)
        }
    }

    @Test fun retiringSlotCancelsInFlightMutationWithoutReplayingIt() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val pending = java.util.concurrent.atomic.AtomicReference<Call>()
        val seed = OkHttpClient.Builder().dns(noDns)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { pending.set(call) }
            }).addInterceptor { chain ->
                calls.incrementAndGet(); entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fake").body("{}".toResponseBody()).build()
            }.build()
        val slot = DnseSlot(DnseConfiguration(false), seed, fakeOnly = true)
        val result = async(Dispatchers.IO) { runCatching { trade(slot).place("a", draft, "token") } }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            slot.retire()
            assertTrue(pending.get().isCanceled())
        } finally { release.countDown() }
        assertTrue(result.await().exceptionOrNull() is IOException)
        assertEquals(1, calls.get())
    }
}
