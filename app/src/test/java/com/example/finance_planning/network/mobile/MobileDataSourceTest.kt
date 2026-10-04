package com.example.finance_planning.network.mobile

import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Invocation
import java.io.File
import java.io.IOException
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class MobileDataSourceTest {
    private class Identity : MobileIdentityProvider {
        var owner: MobileOwner? = MobileOwner("user-a", 1)
        var beforeHeaders: () -> Unit = {}
        var calls = 0
        override fun current() = owner
        override suspend fun headers(owner: MobileOwner): Map<String, String> {
            calls++; beforeHeaders()
            return mapOf("Authorization" to "Bearer fake-${owner.uid}", "X-Firebase-AppCheck" to "fake-app-check")
        }
    }
    private fun example(name: String): JSONObject {
        val rows = JSONObject(File("../docs/contracts/mobile-v1/Backend-base-response.v1.examples.json").readText()).getJSONArray("cases")
        return (0 until rows.length()).map(rows::getJSONObject).single { it.getString("name") == name }.getJSONObject("instance")
    }
    private fun slot(reply: (Request) -> Pair<Int, String>): BackendSlot {
        val client = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = error("Fake Backend must not reach DNS")
        }).addInterceptor { chain ->
            val (status, body) = reply(chain.request())
            val correlation = runCatching { JSONObject(body).getJSONObject("meta").getString("request_id") }.getOrNull()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fake").apply { correlation?.let { header("X-Request-ID", it) } }
                .body(body.toResponseBody()).build()
        }.build()
        return BackendSlot(BackendConfiguration(BackendWireVersion.MOBILE_V1), client, logger = {})
    }

    @Test fun defaultContainerStaysLegacyAndReplacementRetiresItsOldSlot() {
        val clients = NetworkClients()
        val legacy = clients.backend()
        assertSame(legacy, clients.backend())
        assertTrue(runCatching { legacy.mobileDataSource(Identity()) }.isFailure)
        val mobile = clients.backend(BackendConfiguration(BackendWireVersion.MOBILE_V1))
        assertNotSame(legacy.retrofit, mobile.retrofit)
        assertTrue(runCatching { legacy.checkActive() }.exceptionOrNull() is SupersededNetworkContext)
        assertEquals(20_000, mobile.client.connectTimeoutMillis)
        assertEquals(40_000, mobile.client.readTimeoutMillis)
        assertEquals(0, mobile.client.callTimeoutMillis)
        assertEquals(0, mobile.client.writeTimeoutMillis)
        assertFalse(mobile.client.retryOnConnectionFailure)
        clients.invalidateBackend()
        assertTrue(runCatching { mobile.checkActive() }.isFailure)
    }

    private suspend fun invoke(source: MobileBackendDataSource, name: String, args: List<Any?>): Any? =
        suspendCoroutine { continuation ->
            val method = source.javaClass.methods.single { it.name == name }
            try {
                val value = method.invoke(source, *(args + continuation).toTypedArray())
                if (value !== COROUTINE_SUSPENDED) continuation.resume(value)
            } catch (e: java.lang.reflect.InvocationTargetException) { continuation.resumeWithException(e.targetException) }
        }

    @Test fun all26TypedOperationsValidateAndExchangeFrozenExamplesThroughInjectedService() = runBlocking {
        val requests = mutableListOf<Request>()
        val identity = Identity()
        val slot = slot { request ->
            requests += request
            val op = request.tag(Invocation::class.java)!!.method().getAnnotation(MobileOperation::class.java)!!.value
            200 to example(op + "_success").toString()
        }
        val source = slot.mobileDataSource(identity)
        for (method in MobileBackendService::class.java.methods.filter { it.getAnnotation(MobileOperation::class.java) != null }) {
            val op = method.getAnnotation(MobileOperation::class.java)!!.value
            val fixture = example(op + "_request")
            val args = method.parameterAnnotations.mapIndexedNotNull { index, annotations ->
                when {
                    annotations.any { it is retrofit2.http.Path } -> {
                        val name = annotations.filterIsInstance<retrofit2.http.Path>().single().value
                        listOf(fixture.getJSONObject("path").getString(name))
                    }
                    annotations.any { it is retrofit2.http.Query } -> {
                        val name = annotations.filterIsInstance<retrofit2.http.Query>().single().value
                        listOf(clean(fixture.getJSONObject("query").opt(name)))
                    }
                    annotations.any { it is retrofit2.http.Body } ->
                        listOf(MobileDtoCodecs.decode(method.parameterTypes[index], fixture.getJSONObject("body")))
                    else -> null
                }
            }.flatten()
            val result = invoke(source, op, args)
            if (op == "health") assertTrue(result is MobileHealthResult.Success)
            else {
                assertTrue(op, result is OwnedMobileResult<*>)
                assertTrue(op, (result as OwnedMobileResult<*>).result is MobileResult.Success<*>)
                assertEquals(identity.owner, result.owner)
            }
            assertEquals(op, requests.last().tag(Invocation::class.java)!!.method().name)
            if (op != "health") assertEquals("Bearer fake-user-a", requests.last().header("Authorization"))
            else assertNull(requests.last().header("Authorization"))
        }
        assertEquals(26, requests.size)
        assertEquals(25, identity.calls)
    }

    @Test fun sourceIsExplicitPerCallAndInvalidArgumentsDoNotResolveCredentialsOrDispatch() = runBlocking {
        val requests = mutableListOf<Request>()
        val identity = Identity()
        val source = slot { requests += it; 200 to example("batches_success").toString() }.mobileDataSource(identity)
        assertTrue(source.batches(source = "bad-source").result is MobileResult.InvalidRequest)
        assertTrue(source.batches(limit = 101).result is MobileResult.InvalidRequest)
        assertEquals(0, identity.calls); assertTrue(requests.isEmpty())
        source.batches(source = "legacy")
        source.batches()
        assertEquals("legacy", requests[0].url.queryParameter("source"))
        assertNull(requests[1].url.queryParameter("source"))
    }

    @Test fun accountChangeDuringCredentialResolutionPreventsSendingOldRequest() = runBlocking {
        val identity = Identity()
        var calls = 0
        val source = slot { calls++; 200 to example("syncStatus_success").toString() }.mobileDataSource(identity)
        identity.beforeHeaders = { identity.owner = MobileOwner("user-b", 2) }
        val result = source.syncStatus()
        assertEquals(MobileOwner("user-a", 1), result.owner)
        assertSame(MobileResult.ContextChanged, result.result)
        assertEquals(0, calls)
    }

    @Test fun lateReadIsDiscardedButAcknowledgedMutationKeepsOriginalOwner() = runBlocking {
        val identity = Identity()
        val source = slot { request ->
            identity.owner = MobileOwner("user-b", 2)
            val op = request.tag(Invocation::class.java)!!.method().name
            200 to example(op + "_success").toString()
        }.mobileDataSource(identity)
        assertSame(MobileResult.ContextChanged, source.syncStatus().result)
        identity.owner = MobileOwner("user-a", 1)
        val result = source.retryProjection()
        assertEquals(MobileOwner("user-a", 1), result.owner)
        assertTrue(result.result is MobileResult.Success<*>)
    }

    @Test fun unknownPartialServerOutcomeRemainsTypedAndTransportFailureNeverReplays() = runBlocking {
        val body = example("partially_known_report_overall_unknown")
        var calls = 0
        val source = slot { calls++; body.getJSONObject("meta").getInt("http_status") to body.toString() }.mobileDataSource(Identity())
        val failure = source.retryProjection().result as MobileResult.ServerFailure
        assertEquals("UNKNOWN", failure.error.operation!!.database)
        assertEquals(listOf("PLACED_REPORT"), failure.error.operation.committed_steps)
        assertEquals(1, calls)
        calls = 0
        val broken = slot { calls++; throw IOException("fake broken connection") }.mobileDataSource(Identity())
        val transport = broken.retryProjection().result as MobileResult.TransportFailure
        assertTrue(transport.mutationOutcomeUnknown)
        assertEquals(1, calls)
    }

    @Test fun eagerErrorBufferAndSuccessDecodeAreBoundedAndMalformedRepliesStayProtocolFailures() = runBlocking {
        var status = 400
        var body = "x".repeat(4_194_305)
        val source = slot { status to body }.mobileDataSource(Identity())
        assertTrue(source.syncStatus().result is MobileResult.ProtocolFailure)
        status = 200
        assertTrue(source.syncStatus().result is MobileResult.ProtocolFailure)
        body = "{broken"
        assertTrue(source.syncStatus().result is MobileResult.ProtocolFailure)
        status = 204; body = ""
        assertTrue(source.syncStatus().result is MobileResult.ProtocolFailure)
    }

    @Test fun providerCancellationPropagatesWithoutDispatchOrSyntheticAuthFailure() = runBlocking {
        val identity = Identity()
        var calls = 0
        val source = slot { calls++; 200 to "{}" }.mobileDataSource(identity)
        identity.beforeHeaders = { throw CancellationException("fake cancellation") }
        assertTrue(runCatching { source.syncStatus() }.exceptionOrNull() is CancellationException)
        assertEquals(0, calls)
        identity.beforeHeaders = { throw IllegalStateException("fake provider unavailable") }
        assertSame(MobileResult.CredentialsUnavailable, source.syncStatus().result)
        assertEquals(0, calls)
    }

    @Test fun qaStartupCannotConstructAnOrdinaryBackendSlot() {
        val selection = File.createTempFile("mobile-qa-denial", ".json")
        try {
            selection.writeText("{broken")
            com.example.finance_planning.core.QaStartupIsolation.loadPrivateSelection(selection)
            assertTrue(runCatching { NetworkClients().backend() }.exceptionOrNull()
                is com.example.finance_planning.core.QaIsolationDenied)
        } finally {
            selection.delete()
            com.example.finance_planning.core.QaStartupIsolation.loadPrivateSelection(selection)
        }
    }
}
