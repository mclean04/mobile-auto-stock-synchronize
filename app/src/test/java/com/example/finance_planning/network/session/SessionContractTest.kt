package com.example.finance_planning.network.session

import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SessionContractTest {
    private fun contract(name: String) = java.io.File("../docs/contracts/session-v2/session-registration-v2.$name")
    private val device = "11111111-1111-4111-8111-111111111111"
    private val requestId = "22222222-2222-4222-8222-222222222222"
    private val request = SessionRegistrationRequest(device, "SYNTHETIC_FCM_TOKEN_EXAMPLE_ONLY")
    private val success = """{"code":200,"data":{"session":{"pending_sheet_batches":0,"count_capped_at":200,"sheet_writes":false,"role":"uploader"},"registration":{"device_id":"$device","registered":true}},"error":null,"meta":{"contract_version":"mobile-envelope.v2","request_id":"$requestId","http_status":200,"pagination":null}}"""
    private fun error(status: Int = 503, code: String = "future_safe_error", database: String = "UNKNOWN",
                      projection: String = "UNKNOWN", steps: String = "[]") =
        """{"code":$status,"data":null,"error":{"code":"$code","message":"safe message","validation":[],"operation":{"operation_id":"$device","database":"$database","projection":"$projection","environment":null,"committed_steps":$steps}},"meta":{"contract_version":"mobile-envelope.v2","request_id":"$requestId","http_status":$status,"pagination":null}}"""
    private fun slot(enabled: Boolean = true, respond: (Request) -> Response): BackendSlot {
        val seed = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = error("Tests must not use DNS")
        })
            .addInterceptor { respond(it.request()) }.build()
        return BackendSlot(BackendConfiguration(sessionEnabled = enabled), seed, logger = {})
    }
    private fun reply(request: Request, text: String = success, status: Int = 200,
                      correlation: String = requestId): Response = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
        .header("X-Request-ID", correlation).body(text.toResponseBody()).build()

    @Test fun backendPinnedSchemasAndExamplesMatchTheConcreteOperation() = runBlocking {
        val pins = mapOf(
            "examples.json" to "8afe1511b62721cf626e1c693d56728f75911b66eb0805744c396ef52d18516e",
            "openapi.json" to "844f31d19b095cf64ea36d050e05f67c2a403c1096df4d66cd7fa1498d735bbf",
            "request.schema.json" to "7c4955acf2cb9dc2c5937878481bf8e977febd5091e7bf0ce7401b37682faaa6",
            "response.schema.json" to "a7e8b48096e5de2136ee32bb82e4a5deb3d6289e7639cae3f97b2d69fa24b151")
        pins.forEach { (name, hash) ->
            assertEquals(name, hash, java.security.MessageDigest.getInstance("SHA-256")
                .digest(contract(name).readBytes()).joinToString("") { "%02x".format(it) })
        }
        val openapi = com.google.gson.JsonParser.parseString(contract("openapi.json").readText()).asJsonObject
        val operation = openapi.getAsJsonObject("paths").getAsJsonObject("/mobile/v2/session").getAsJsonObject("post")
        assertEquals("establishSession", operation["operationId"].asString)
        val schema = com.google.gson.JsonParser.parseString(contract("request.schema.json").readText()).asJsonObject
        assertEquals(setOf("device_id", "fcm_token"), schema.getAsJsonArray("required").map { it.asString }.toSet())
        assertFalse(schema["additionalProperties"].asBoolean)
        val fixture = com.google.gson.JsonParser.parseString(contract("examples.json").readText()).asJsonObject
        assertEquals(fixture.getAsJsonObject("request")["body"], com.google.gson.JsonParser.parseString(SessionGson.gson.toJson(request)))
        for (case in fixture.getAsJsonArray("responses")) {
            val row = case.asJsonObject
            val status = row["http_status"].asInt
            val remote = slot { reply(it, row["body"].toString(), status,
                row.getAsJsonObject("headers")["X-Request-ID"].asString) }.sessionDataSource { emptyMap() }
            val result = runCatching { remote.establish(request, SessionRequestContext { true }) }
            if (status == 200) assertEquals(device, result.getOrThrow().registration.device_id)
            else {
                val failure = result.exceptionOrNull() as SessionServerFailure
                assertEquals(status, failure.response.code)
                assertEquals(row.getAsJsonObject("body").getAsJsonObject("error")["code"].asString,
                    failure.response.error!!.code)
            }
        }
    }

    @Test fun nullOperationValidationAndUnicodeLengthsFollowPinnedSchema() {
        val issue = """{"location":"body","pointer":"/fcm_token","code":"invalid_value","message":"safe"}"""
        val raw = error(422).replace("\"validation\":[]", "\"validation\":[$issue]")
            .replace(Regex("\"operation\":\\{[^}]+}"), "\"operation\":null")
        assertNull(SessionGson.decode(raw.toResponseBody()).error!!.operation)
        assertEquals("/fcm_token", SessionGson.decode(raw.toResponseBody()).error!!.validation.single().pointer)
        val unicode = "\uD83D\uDE00".repeat(300)
        assertEquals(unicode, SessionGson.decode(raw.replace("safe message", unicode).toResponseBody()).error!!.message)
        for (invalid in listOf(raw.replace("safe message", unicode + "x"),
            raw.replace("/fcm_token", "/bad~2escape"), raw.replace("\"operation\":null", "\"operation\":{}"),
            raw.replace("[$issue]", "[${List(51) { issue }.joinToString(",")}]")))
            assertTrue(runCatching { SessionGson.decode(invalid.toResponseBody()) }.isFailure)
    }

    @Test fun typedRequestAndResponseShareSlotPreserveAuthAndUseOnePost() = runBlocking {
        val calls = mutableListOf<Request>()
        val slot = slot { calls += it; reply(it) }
        val source = slot.sessionDataSource { mapOf("Authorization" to "Bearer SYNTHETIC", "X-Firebase-AppCheck" to "SYNTHETIC_CHECK") }
        val result = source.establish(request, SessionRequestContext { true })
        assertEquals(SessionRole.uploader, result.session.role)
        assertEquals(device, result.registration.device_id)
        assertEquals(1, calls.size)
        val sent = calls.single()
        assertEquals("POST", sent.method); assertEquals("/mobile/v2/session", sent.url.encodedPath)
        assertEquals("Bearer SYNTHETIC", sent.header("Authorization"))
        assertEquals("SYNTHETIC_CHECK", sent.header("X-Firebase-AppCheck"))
        val body = okio.Buffer().also { sent.body!!.writeTo(it) }.readUtf8()
        assertEquals("""{"device_id":"$device","fcm_token":"SYNTHETIC_FCM_TOKEN_EXAMPLE_ONLY"}""", body)
        assertFalse(request.toString().contains(request.fcm_token))
        assertFalse(slot.client.retryOnConnectionFailure)
        assertFalse(slot.client.followRedirects)
        assertNotNull(slot.legacy)
    }

    @Test fun rejectsMissingNullDuplicateExtraAndCoercedFields() {
        val invalid = listOf(
            success.replace("\"error\":null,", ""),
            success.replace("\"pagination\":null", "\"pagination\":{}"),
            success.replace("\"role\":\"uploader\"", "\"role\":null"),
            success.replace("\"role\":\"uploader\"", "\"role\":\"admin\",\"role\":\"uploader\""),
            success.replace("\"registered\":true", "\"registered\":true,\"secret\":\"bad\""),
            success.replace("\"registered\":true", "\"registered\":false"),
            success.replace("\"sheet_writes\":false", "\"sheet_writes\":\"false\""),
            success.replace("\"pending_sheet_batches\":0", "\"pending_sheet_batches\":\"0\""),
            success.replace("\"pending_sheet_batches\":0", "\"pending_sheet_batches\":0.5"),
            success.replace("\"pending_sheet_batches\":0", "\"pending_sheet_batches\":1e-1"),
            success.replace("\"pending_sheet_batches\":0", "\"pending_sheet_batches\":201"),
            success.replace("\"pending_sheet_batches\":0", "\"pending_sheet_batches\":-1"),
            success.replace("\"count_capped_at\":200", "\"count_capped_at\":199"),
            success.replace("\"code\":200", "\"code\":201"),
            success.replace("\"http_status\":200", "\"http_status\":503"),
            success.replace("mobile-envelope.v2", "mobile-envelope.v1"),
            success.replace(requestId, "22222222-2222-1222-8222-222222222222"),
            success.replace(device, "not-a-uuid"), success + "{}", "null", "{}",
            success.replace("\"error\":null", "\"error\":{}"),
            error().replace("\"operation\":", "\"unknown\":null,\"operation\":"),
            error(200), error(database = "COMMITTED"), error(steps = "[\"DEVICE_REGISTRATION\"]"),
            error(database = "COMMITTED", projection = "NOT_APPLICABLE", steps = "[\"DEVICE_REGISTRATION\",\"DEVICE_REGISTRATION\"]")
        )
        invalid.forEachIndexed { index, json ->
            assertTrue("invalid case $index", runCatching { SessionGson.decode(json.toResponseBody()) }.exceptionOrNull() is SessionProtocolFailure)
        }
        assertEquals(200, SessionGson.decode(success.replace("\"pending_sheet_batches\":0", "\"pending_sheet_batches\":200").toResponseBody()).data!!.session.pending_sheet_batches)
        for (integer in listOf("200", "200.0", "2e2")) {
            val body = success.replace("\"pending_sheet_batches\":0", "\"pending_sheet_batches\":$integer")
                .replace("\"code\":200", "\"code\":$integer")
            assertEquals(200, SessionGson.decode(body.toResponseBody()).code)
        }
    }

    @Test fun errorsPreserveUnknownCodeStatusAndConfirmedOrUnknownOutcomeWithoutReplay() = runBlocking {
        for ((database, projection, steps) in listOf(
            Triple("UNKNOWN", "UNKNOWN", "[]"),
            Triple("NOT_APPLIED", "NOT_APPLICABLE", "[]"),
            Triple("COMMITTED", "NOT_APPLICABLE", "[\"DEVICE_REGISTRATION\"]"))) {
            var calls = 0
            val remote = slot { calls++; reply(it, error(database = database, projection = projection, steps = steps), 503) }.sessionDataSource { emptyMap() }
            val failure = runCatching { remote.establish(request, SessionRequestContext { true }) }.exceptionOrNull() as SessionServerFailure
            assertEquals(503, failure.response.code)
            assertEquals("future_safe_error", failure.response.error!!.code)
            assertEquals(database, failure.response.error.operation!!.database.name)
            assertEquals(1, calls)
        }
    }

    @Test fun revokedIncarnationIsTyped409WithoutIdReplacementOrReplay() = runBlocking {
        val amendment = contract("revocation-amendment.md")
        assertEquals("2383037bac202f68ce031d00eaef01be6f7505e324dc4e4456ebb57749b43d71",
            java.security.MessageDigest.getInstance("SHA-256").digest(amendment.readBytes())
                .joinToString("") { "%02x".format(it) })
        val calls = mutableListOf<Request>()
        val remote = slot {
            calls += it
            reply(it, error(409, "device_registration_revoked", "NOT_APPLIED", "NOT_APPLICABLE"), 409)
        }.sessionDataSource { emptyMap() }
        val failure = runCatching { remote.establish(request, SessionRequestContext { true }) }
            .exceptionOrNull() as SessionServerFailure
        assertEquals(409, failure.response.code)
        assertEquals(409, failure.response.meta.http_status)
        assertNull(failure.response.data)
        val error = failure.response.error!!
        assertEquals("device_registration_revoked", error.code)
        assertEquals(device, error.operation!!.operation_id)
        assertEquals(DatabaseOutcome.NOT_APPLIED, error.operation.database)
        assertEquals(ProjectionOutcome.NOT_APPLICABLE, error.operation.projection)
        assertTrue(error.operation.committed_steps.isEmpty())
        assertNull(error.operation.environment)
        assertEquals(1, calls.size)
        assertEquals(device, request.device_id)
        assertEquals("/mobile/v2/session", calls.single().url.encodedPath)
        assertEquals(SessionGson.gson.toJson(request),
            okio.Buffer().also { calls.single().body!!.writeTo(it) }.readUtf8())
    }

    @Test fun committedReplyAfterLogoutFenceCannotPublishStaleRegistration() = runBlocking {
        var current = true
        var calls = 0
        val remote = slot {
            calls++
            current = false
            reply(it, error(409, "planning_source_conflict", "COMMITTED", "NOT_APPLICABLE",
                "[\"DEVICE_REGISTRATION\"]"), 409)
        }.sessionDataSource { emptyMap() }
        val failure = runCatching { remote.establish(request, SessionRequestContext { current }) }.exceptionOrNull()
        assertTrue(failure is SupersededNetworkContext)
        assertFalse(failure is SessionServerFailure)
        assertEquals(1, calls)
        assertEquals(device, request.device_id)
    }

    @Test fun correlationOwnerHttpAndRetiredContextFailuresDoNotPublish() = runBlocking {
        for ((json, status, correlation) in listOf(
            Triple(success, 201, requestId), Triple(success, 200, "mismatch"),
            Triple(success.replace(device, "33333333-3333-4333-8333-333333333333"), 200, requestId))) {
            var calls = 0
            val remote = slot { calls++; reply(it, json, status, correlation) }.sessionDataSource { emptyMap() }
            assertTrue(runCatching { remote.establish(request, SessionRequestContext { true }) }.exceptionOrNull() is SessionProtocolFailure)
            assertEquals(1, calls)
        }
        var current = true; var calls = 0
        val slot = slot { calls++; current = false; reply(it) }
        val remote = slot.sessionDataSource { emptyMap() }
        assertTrue(runCatching { remote.establish(request, SessionRequestContext { current }) }.exceptionOrNull() is SupersededNetworkContext)
        assertEquals(1, calls)
        current = true
        val changedDuringHeaders = slot.sessionDataSource { current = false; emptyMap() }
        assertTrue(runCatching { changedDuringHeaders.establish(request, SessionRequestContext { current }) }.exceptionOrNull() is SupersededNetworkContext)
        assertEquals(1, calls)
        current = true; slot.retire()
        assertTrue(runCatching { remote.establish(request, SessionRequestContext { current }) }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun noFallbackOnTransportFailureAndInactiveRouteCannotSend() = runBlocking {
        var calls = 0
        val failed = slot { calls++; throw IOException("synthetic disconnect") }.sessionDataSource { emptyMap() }
        assertTrue(runCatching { failed.establish(request, SessionRequestContext { true }) }.isFailure)
        assertEquals(1, calls)
        val inactive = slot(enabled = false) { calls++; reply(it) }
        assertTrue(runCatching { inactive.sessionDataSource { emptyMap() } }.isFailure)
        assertTrue(runCatching { inactive.session.establishSession(emptyMap(), SessionRequestContext { true }, request) }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun oversizedSuccessAndErrorsFailClosedAndInvalidRequestNeverSends() = runBlocking {
        for (status in listOf(200, 503)) {
            var calls = 0
            val remote = slot { calls++; reply(it, " ".repeat(4_194_305), status) }.sessionDataSource { emptyMap() }
            assertTrue(runCatching { remote.establish(request, SessionRequestContext { true }) }.isFailure)
            assertEquals(1, calls)
        }
        var calls = 0
        val remote = slot { calls++; reply(it) }.sessionDataSource { emptyMap() }
        for (invalid in listOf(request.copy(device_id = "bad"), request.copy(fcm_token = "short"), request.copy(fcm_token = "a".repeat(4097))))
            assertTrue(runCatching { remote.establish(invalid, SessionRequestContext { true }) }.isFailure)
        assertEquals(0, calls)
    }
}
