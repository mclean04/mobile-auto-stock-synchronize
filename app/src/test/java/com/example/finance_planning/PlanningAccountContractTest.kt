package com.example.finance_planning

import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Active planning_backend calls over real Retrofit/Gson, terminated by an in-memory HTTP fixture. */
class PlanningAccountContractTest {
    private val id = "12345678-abcd-4abc-8abc-123456789012"
    private val token = "SYNTHETIC-FCM-TOKEN-123456"
    private val status = """{"pending_sheet_batches":0,"count_capped_at":200,"sheet_writes":false,"role":"uploader"}"""
    private fun api(requests: MutableList<Request> = mutableListOf(),
                    context: ((String, String) -> SessionRequestContext)? = null,
                    reply: (Request) -> Pair<Int, String>): BackendApi {
        val seed = OkHttpClient.Builder()
            .dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = error("Real network forbidden") })
            .addInterceptor {
                requests += it.request()
                val (code, body) = reply(it.request())
                Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
                    .body(body.toResponseBody()).build()
            }.build()
        return BackendApi(Transport(seed), { mapOf("Authorization" to "Bearer SYNTHETIC", "X-Firebase-AppCheck" to "SYNTHETIC") }, requestContext = context)
    }
    @Test fun healthAcceptsRawPlanningContractAndNeverAuthenticates() = runBlocking {
        val calls = mutableListOf<Request>()
        val response = api(calls) { 200 to """{"status":"ok","storage":"firestore","future":{}}""" }.health()
        assertEquals("ok", response.status); assertEquals("firestore", response.storage)
        assertEquals("/health", calls.single().url.encodedPath)
        assertNull(calls.single().header("Authorization")); assertNull(calls.single().header("X-Firebase-AppCheck"))
        assertEquals("application/json", calls.single().header("Accept"))
        assertEquals(BackendEndpoint::class.java, calls.single().tag(retrofit2.Invocation::class.java)!!.method().declaringClass)
        for (bad in listOf("{}", "null", "[]", "{", """{"status":"ok"}""",
            """{"status":"ok","storage":null}""", """{"status":"ok","storage":"sql"}""",
            """{"code":200,"data":{"status":"ok","database":"ok"},"error":null,"meta":{}}""")) {
            assertTrue(bad, runCatching { api { 200 to bad }.health() }.isFailure)
        }
    }
    @Test fun statusRejectsMissingNullCoercedAndOutOfRangeFields() = runBlocking {
        val valid = api { 200 to status }.syncStatus()
        assertEquals(0, valid.pending_sheet_batches); assertEquals(false, valid.sheet_writes)
        assertEquals("uploader", valid.role)
        for (key in listOf("pending_sheet_batches", "count_capped_at", "sheet_writes", "role")) {
            for (raw in listOf(JSONObject(status).apply { remove(key) }.toString(), JSONObject(status).put(key, JSONObject.NULL).toString()))
                assertTrue("$key required", runCatching { api { 200 to raw }.syncStatus() }.isFailure)
        }
        for ((key, value) in listOf("pending_sheet_batches" to -1, "pending_sheet_batches" to 201,
            "pending_sheet_batches" to 0.5, "pending_sheet_batches" to "1", "pending_sheet_batches" to 2147483648L,
            "count_capped_at" to 199, "sheet_writes" to "false", "sheet_writes" to 0, "role" to "owner", "role" to 1)) {
            assertTrue("$key=$value", runCatching { api { 200 to JSONObject(status).put(key, value).toString() }.syncStatus() }.isFailure)
        }
        assertEquals(1, api { 200 to status.replace(":0,", ":1.0,") }.syncStatus().pending_sheet_batches)
        assertTrue(runCatching { api { 200 to """{"code":200,"data":$status,"error":null,"meta":{}}""" }.syncStatus() }.isFailure)
    }
    @Test fun deviceCallsUseTypedBodyAndNormalizedIdWithBodylessDelete() = runBlocking {
        val calls = mutableListOf<Request>()
        val api = api(calls) { request -> 200 to """{"device_id":"$id","registered":${request.method == "PUT"},"extra":true}""" }
        assertTrue(api.registerDevice(id.uppercase(), token).registered!!)
        assertFalse(api.removeDevice(id.uppercase()).registered!!)
        assertEquals(listOf("PUT", "DELETE"), calls.map { it.method })
        assertTrue(calls.all { it.url.encodedPath == "/v1/devices/$id" && it.header("Authorization") == "Bearer SYNTHETIC" })
        assertTrue(calls.first().tag(retrofit2.Invocation::class.java)!!.arguments()[1] is PlanningDeviceRequest)
        val body = Buffer(); calls.first().body!!.writeTo(body)
        val decoded = JSONObject(body.readUtf8())
        assertEquals(1, decoded.length()); assertEquals(token, decoded.getString("fcm_token"))
        assertNull(calls.last().body)
        for (bad in listOf("short", "x".repeat(4097))) {
            assertTrue(runCatching { api.registerDevice(id, bad) }.isFailure)
        }
        assertEquals(2, calls.size)
    }
    @Test fun deviceAcknowledgementMustMatchIdAndExpectedBoolean() = runBlocking {
        for (register in listOf(false, true)) {
            for (raw in listOf("{}", "null", """{"device_id":"$id"}""",
                """{"device_id":"$id","registered":null}""",
                """{"device_id":"$id","registered":"$register"}""",
                """{"device_id":"$id","registered":${!register}}""",
                """{"device_id":"00000000-0000-4000-8000-000000000001","registered":$register}""")) {
                val calls = mutableListOf<Request>(); val api = api(calls) { 200 to raw }
                val error = runCatching { if (register) api.registerDevice(id, token) else api.removeDevice(id) }.exceptionOrNull()
                assertTrue(raw, error is PlanningCallFailure)
                assertEquals(PlanningMutationOutcome.UNKNOWN, (error as PlanningCallFailure).outcome)
                assertEquals(1, calls.size)
            }
        }
    }
    @Test fun rawServerErrorsPreserveCodesStatusAndUncertainMutationWithoutRetry() = runBlocking {
        for ((code, detail) in listOf(409 to "device_registration_revoked", 404 to "device_not_registered",
            409 to "device_owned_by_another_user", 503 to "planning_source_changed", 401 to "invalid_firebase_token")) {
            val calls = mutableListOf<Request>()
            val error = runCatching { api(calls) { code to """{"detail":"$detail"}""" }.removeDevice(id) }.exceptionOrNull() as PlanningHttpFailure
            assertEquals(code, error.status); assertEquals(detail, error.code); assertEquals(detail, error.error!!.detail)
            assertEquals(PlanningMutationOutcome.UNKNOWN, error.outcome); assertEquals(1, calls.size)
        }
        for (raw in listOf("{}", "null", """{"detail":null}""", """{"detail":123}""", "bad-json", """{"detail":[]} """, """{"code":503,"data":null,"error":{"code":"dependency"},"meta":{}}""")) {
            val error = runCatching { api { 503 to raw }.registerDevice(id, token) }.exceptionOrNull() as PlanningHttpFailure
            assertEquals(503, error.status); assertNull(error.error); assertNull(error.code); assertEquals(PlanningMutationOutcome.UNKNOWN, error.outcome)
        }
    }
    @Test fun staleReadsAreRejectedAndAcknowledgedMutationsStayWithTheirCaller() = runBlocking {
        for (mutation in listOf(false, true)) {
            var current = true
            val calls = mutableListOf<Request>()
            val api = api(calls, { _, _ -> SessionRequestContext { current } }) {
                current = false
                200 to if (mutation) """{"device_id":"$id","registered":false}""" else status
            }
            val result = runCatching { if (mutation) api.removeDevice(id) else api.syncStatus() }
            if (mutation) assertTrue(result.isSuccess) else assertTrue(result.exceptionOrNull() is SupersededNetworkContext)
            assertEquals(1, calls.size)
        }
        var calls = 0
        val api = api(context = { _, _ -> SessionRequestContext { false } }) { calls++; 200 to status }
        assertTrue(runCatching { api.syncStatus() }.exceptionOrNull() is SupersededNetworkContext)
        assertEquals(0, calls)
    }
    @Test fun ioFailureAndOversizedSuccessNeverReplayDeviceMutations() = runBlocking {
        var calls = 0
        val api = api { calls++; throw IOException("synthetic incomplete response") }
        val error = runCatching { api.removeDevice(id) }.exceptionOrNull() as PlanningCallFailure
        assertEquals(PlanningMutationOutcome.UNKNOWN, error.outcome); assertEquals(1, calls)
        val big = """{"device_id":"$id","registered":true,"extra":"""" + "x".repeat(4 * 1024 * 1024) + "\"}"
        val requests = mutableListOf<Request>()
        assertTrue(runCatching { api(requests) { 200 to big }.registerDevice(id, token) }.exceptionOrNull() is PlanningCallFailure)
        assertEquals(1, requests.size)
    }
}
