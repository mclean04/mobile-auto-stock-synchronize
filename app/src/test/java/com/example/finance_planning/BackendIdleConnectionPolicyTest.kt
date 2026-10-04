package com.example.finance_planning

import com.example.finance_planning.core.Contracts
import com.example.finance_planning.network.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real loopback sockets with synthetic responses; never DNS/provider credentials or a device. */
class BackendIdleConnectionPolicyTest {
    @Before fun text() { TestText.install() }
    private val health = """{"status":"ok","storage":"firestore"}"""
    private val status = """{"pending_sheet_batches":0,"count_capped_at":200,"sheet_writes":false,"role":"uploader"}"""

    private inner class Fixture(http2: Boolean = false) : Closeable {
        val server = MockWebServer().apply {
            if (http2) protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
            start(InetAddress.getLoopbackAddress(), 0)
        }
        private val seed = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    check(hostname == server.hostName) { "Only loopback fixture DNS is permitted" }
                    return listOf(InetAddress.getLoopbackAddress())
                }
            })
            .readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS)
            .apply { if (http2) protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)) }
            // Test-only rewrite AFTER the production slot origin/context/auth guards.
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .url(server.url(chain.request().url.encodedPath)).build())
            }.build()
        val slot = BackendSlot(BackendConfiguration(), seed, logger = {})
        suspend fun raw(method: String = "GET", client: OkHttpClient = slot.client): retrofit2.Response<ResponseBody> =
            httpService(Contracts.BACKEND + "/", client).request(Contracts.BACKEND + "/fixture", method,
                emptyMap(), if (method in listOf("POST", "PUT", "PATCH")) "{}".toRequestBody() else null)
        override fun close() {
            slot.retire(); slot.client.connectionPool.evictAll()
            slot.client.dispatcher.executorService.shutdownNow(); server.shutdown()
        }
    }

    @Test fun counterfactualOrdinaryPoolReproducesEofThenLaterSuccess() = runBlocking {
        Fixture().use { f ->
            val idleExpired = AtomicBoolean(false)
            f.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (idleExpired.get() && request.sequenceNumber > 0)
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else MockResponse().setBody(health)
            }
            // Same Backend slot, with only the new connection-prevention interceptor removed.
            val before = f.slot.client.newBuilder().apply {
                networkInterceptors().removeAll { it is BackendHttp1ConnectionPolicy }
            }.build()
            repeat(2) { f.raw(client = before).body()!!.use { assertEquals(health, it.string()) } }
            // Controlled expiry: emulate a peer discarding the next request on an old idle socket.
            idleExpired.set(true)
            val failure = runCatching { f.raw(client = before).body()!!.use { it.string() } }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(generateSequence(failure) { it.cause }.any { it is EOFException })
            f.raw(client = before).body()!!.use { assertEquals(health, it.string()) }
            assertEquals(listOf(0, 1, 2, 0), List(4) { f.server.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber })
            assertEquals(4, f.server.requestCount) // The failed request was not silently replayed.
        }
    }

    @Test fun localHttp1PreventsStaleReuseWhileRemoteVariantsKeepOrdinaryPooling() = runBlocking {
        Fixture().use { f ->
            assertEquals(BuildConfig.LOCAL_BACKEND,
                f.slot.client.networkInterceptors.any { it is BackendHttp1ConnectionPolicy })
            val idleExpired = AtomicBoolean(false)
            f.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (idleExpired.get() && request.sequenceNumber > 0)
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else MockResponse().setBody(health)
            }
            repeat(2) { f.raw().body()!!.use { assertEquals(health, it.string()) } }
            // Local prevention withstands controlled peer expiry. Remote variants retain reuse;
            // the counterfactual test separately demonstrates their unmitigated stale-pool failure.
            idleExpired.set(BuildConfig.LOCAL_BACKEND)
            repeat(2) { f.raw().body()!!.use { assertEquals(health, it.string()) } }
            repeat(4) { index ->
                val request = f.server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(if (BuildConfig.LOCAL_BACKEND) 0 else index, request.sequenceNumber)
                if (BuildConfig.LOCAL_BACKEND) assertEquals("close", request.getHeader("Connection"))
                else assertNotEquals("close", request.getHeader("Connection"))
            }
            assertEquals(4, f.server.requestCount)
            assertEquals(if (BuildConfig.LOCAL_BACKEND) 0 else 1,
                f.slot.client.connectionPool.idleConnectionCount())
            assertFalse(f.slot.client.retryOnConnectionFailure)
        }
    }

    @Test fun successfulMutationsCloseOnlyLocalSocketsBeforeNextRead() = runBlocking {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) Fixture().use { f ->
            repeat(2) { f.server.enqueue(MockResponse().setBody(health)) }
            f.raw(method).body()!!.use { assertEquals(health, it.string()) }
            f.raw().body()!!.use { assertEquals(health, it.string()) }
            val mutation = f.server.takeRequest(2, TimeUnit.SECONDS)!!
            val read = f.server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(method, mutation.method)
            assertEquals("GET", read.method)
            assertEquals(0, mutation.sequenceNumber)
            assertEquals(if (BuildConfig.LOCAL_BACKEND) 0 else 1, read.sequenceNumber)
            for (request in listOf(mutation, read)) {
                if (BuildConfig.LOCAL_BACKEND) assertEquals("close", request.getHeader("Connection"))
                else assertNotEquals("close", request.getHeader("Connection"))
            }
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun genuineRepeatedDisconnectsStayVisibleAndBoundedToOneDispatchEach() = runBlocking {
        Fixture().use { f ->
            repeat(2) { f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
            repeat(2) { assertTrue(runCatching { f.raw().body()?.use { it.string() } }.exceptionOrNull() is IOException) }
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun postPutPatchDeleteAreNeverReplayedAfterDispatch() = runBlocking {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) Fixture().use { f ->
            f.server.enqueue(MockResponse().setBody(health))
            f.raw().body()!!.close()
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            f.server.enqueue(MockResponse().setBody("must-not-be-consumed"))
            assertTrue(runCatching { f.raw(method).body()?.use { it.string() } }.exceptionOrNull() is IOException)
            assertEquals(2, f.server.requestCount)
            f.server.takeRequest(2, TimeUnit.SECONDS)
            assertEquals(method, f.server.takeRequest(2, TimeUnit.SECONDS)!!.method)
        }
    }

    @Test fun httpErrorsAndPartialBodiesAreNotRetriedOrHidden() = runBlocking {
        Fixture().use { f ->
            for (code in listOf(401, 403, 503)) {
                f.server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "0").setBody("{}"))
                val response = f.raw()
                assertEquals(code, response.code()); response.errorBody()?.close()
            }
            f.server.enqueue(MockResponse().setBody("partial".repeat(100))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val failure = runCatching { f.raw().body()!!.use { it.string() } }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(4, f.server.requestCount)
        }
    }

    @Test fun cancellationAndSlotRetirementDoNotStartAnotherExchange() = runBlocking {
        for (retire in listOf(false, true)) Fixture().use { f ->
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async(Dispatchers.Default) { runCatching { f.raw().body()?.use { it.string() } } }
            assertNotNull(f.server.takeRequest(2, TimeUnit.SECONDS))
            if (retire) {
                f.slot.retire()
                assertTrue(withTimeout(3_000) { pending.await() }.isFailure)
                assertTrue(runCatching { f.raw() }.exceptionOrNull() is SupersededNetworkContext)
            } else withTimeout(3_000) { pending.cancelAndJoin() }
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun ownerLogoutOrSourceChangeDiscardsTheLateRead() = runBlocking {
        for (replacement in listOf("logged-out", "another-owner", "another-source")) Fixture().use { f ->
            val context = AtomicReference("owner-a/source-a")
            f.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    context.set(replacement)
                    return MockResponse().setBody(status)
                }
            }
            val api = BackendApi(Transport(slotProvider = { f.slot }), {
                mapOf("Authorization" to "Bearer SYNTHETIC-COMPONENT-ONLY")
            }, requestContext = { _, _ -> val captured = context.get(); SessionRequestContext { context.get() == captured } })
            assertTrue(runCatching { api.syncStatus() }.exceptionOrNull() is SupersededNetworkContext)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun http2PoolingAndDnsePolicyStayUnchanged() = runBlocking {
        Fixture(http2 = true).use { f ->
            repeat(2) { f.server.enqueue(MockResponse().setBody(health)); f.raw().body()!!.use { it.string() } }
            val requests = List(2) { f.server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(listOf(0, 1), requests.map { it.sequenceNumber })
            requests.forEach { assertNull(it.getHeader("Connection")) }
            assertEquals(1, f.slot.client.connectionPool.idleConnectionCount())
        }
        val dnse = DnseSlot(DnseConfiguration(false), logger = {})
        assertFalse(dnse.client.networkInterceptors.any { it is BackendHttp1ConnectionPolicy })
        assertFalse(dnse.client.retryOnConnectionFailure)
        dnse.retire()
    }
}
