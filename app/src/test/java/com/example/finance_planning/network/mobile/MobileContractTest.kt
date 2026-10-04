package com.example.finance_planning.network.mobile

import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

class MobileContractTest {
    private fun document(name: String) = JSONObject(File("../docs/contracts/mobile-v1", name).readText())
    private fun cases() = document("Backend-base-response.v1.examples.json").getJSONArray("cases")
    private fun example(name: String): JSONObject = cases().let { rows ->
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("name") == name }.getJSONObject("instance")
    }
    private fun reference(ref: String) = if ('#' in ref) ref.substringAfterLast('/') else "ApiResponse"
    private fun comparable(value: Any?): Any? = when (val item = clean(value)) {
        is JSONObject -> item.keys().asSequence().associateWith { comparable(item.get(it)) }
        is org.json.JSONArray -> (0 until item.length()).map { comparable(item.get(it)) }
        is Number -> item.toString().toBigDecimal().stripTrailingZeros()
        else -> item
    }

    @Test fun backendValidAndInvalidFixturesAgreeWithAndroidChecks() {
        val rows = cases(); var valid = 0; var invalid = 0
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val result = runCatching { ContractChecks.check(reference(row.getString("schema_ref")), row.get("instance")) }
            assertEquals(row.getString("name"), row.getBoolean("valid"), result.isSuccess)
            if (result.isSuccess) valid++ else invalid++
        }
        assertEquals(211, valid); assertEquals(20, invalid)
    }

    @Test fun all26OperationsHaveConcreteRoundTrippingDtosAndValidFixedRetrofitDeclarations() {
        val schema = document("Backend-mobile-endpoints.v1.schema.json").getJSONObject("definitions")
        val endpoints = document("Backend-mobile-endpoints.v1.bindings.json").getJSONArray("endpoints")
        val methods = MobileBackendService::class.java.methods.filter { it.getAnnotation(MobileOperation::class.java) != null }
        assertEquals(26, methods.size)
        Retrofit.Builder().baseUrl("https://example.invalid/").addConverterFactory(MobileContractConverter())
            .validateEagerly(true).build().create(MobileBackendService::class.java)
        for (i in 0 until endpoints.length()) {
            val endpoint = endpoints.getJSONObject(i); val op = endpoint.getString("operation")
            assertEquals(1, methods.count { it.getAnnotation(MobileOperation::class.java)?.value == op })
            val responseName = reference(endpoint.getJSONObject("responses").getJSONObject("200").getString("\$ref"))
            val success = example(op + "_success")
            val data = if (op == "health") success else success.getJSONObject("data")
            val typeName = if (op == "health") "HealthDto" else reference(schema.getJSONObject(responseName)
                .getJSONArray("allOf").getJSONObject(1).getJSONObject("properties").getJSONObject("data").getString("\$ref"))
            val decoded = MobileDtoCodecs.decode(Class.forName("com.example.finance_planning.network.mobile.$typeName"), data)
            assertEquals(op, comparable(data), comparable(decoded.toJson()))
        }
    }

    @Test fun absenceNullAndDecimalStringsRemainDistinct() {
        val source = example("placedOrder_request").getJSONObject("body").getJSONObject("order")
        source.remove("price_vnd")
        val absent = Order.decode(source)
        assertSame(WireField.Absent, absent.price_vnd); assertFalse(absent.toJson().has("price_vnd"))
        source.put("price_vnd", JSONObject.NULL)
        val explicit = Order.decode(source)
        assertEquals(WireField.Present<WireScalar?>(null), explicit.price_vnd)
        assertTrue(explicit.toJson().has("price_vnd")); assertTrue(explicit.toJson().isNull("price_vnd"))
        val intent = example("planningIntent_success").getJSONObject("data")
        assertEquals(intent.getString("limit_price_vnd"), PlanningIntentDto.decode(intent).limit_price_vnd)
    }

    @Test fun unknownPartialAndCommittedErrorsPreserveActualState() {
        for (name in listOf("unknown_mutation_no_invented_id", "partially_known_report_overall_unknown",
                "known_legacy_commit_projection_pending", "unknown_code_remains_error")) {
            val json = example(name); val status = json.getJSONObject("meta").getInt("http_status")
            val raw = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://example.invalid/").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("unit")
                .header("X-Request-ID", json.getJSONObject("meta").getString("request_id")).build()
            val response = Response.error<MobileSuccess<SyncStatusDto>>(json.toString().toResponseBody(), raw)
            val result = MobileResultMapper.map(response) as MobileResult.ServerFailure
            assertEquals(status, result.status); assertEquals(comparable(json.getJSONObject("error")), comparable(result.error.toJson()))
            if (name == "unknown_mutation_no_invented_id") assertNull(result.error.operation!!.operation_id)
            if (name == "partially_known_report_overall_unknown") {
                assertEquals("UNKNOWN", result.error.operation!!.database)
                assertEquals(listOf("PLACED_REPORT"), result.error.operation.committed_steps)
            }
        }
    }

    @Test fun converterRejectsWrongVersionMissingFieldsAnd204Envelope() {
        val type = object : ParameterizedType {
            override fun getRawType(): Type = MobileSuccess::class.java
            override fun getActualTypeArguments(): Array<Type> = arrayOf(SyncStatusDto::class.java)
            override fun getOwnerType(): Type? = null
        }
        val decoder = MobileContractConverter().responseBodyConverter(type, arrayOf(MobileOperation("syncStatus")),
            Retrofit.Builder().baseUrl("https://example.invalid/").build())!!
        val valid = example("syncStatus_success")
        assertEquals("mobile-envelope.v1", (decoder.convert(valid.toString().toResponseBody()) as MobileSuccess<*>).meta.contract_version)
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("meta").put("contract_version", "guessed-v2") },
            { it.getJSONObject("data").remove("role") },
            { it.getJSONObject("meta").put("http_status", 204) }
        )) {
            val changed = JSONObject(valid.toString()); mutate(changed)
            assertTrue(runCatching { decoder.convert(changed.toString().toResponseBody()) }.exceptionOrNull() is MobileContractViolation)
        }
    }

    @Test fun mismatchedCorrelationAndProxyFailureRetainObservedStatus() {
        val json = example("syncStatus_success")
        val body = MobileSuccess(SyncStatusDto.decode(json.getJSONObject("data")), Meta.decode(json.getJSONObject("meta")))
        val mismatch = Response.success(body, Headers.headersOf("X-Request-ID", "22222222-2222-4222-8222-222222222222"))
        assertTrue(MobileResultMapper.map(mismatch) is MobileResult.ProtocolFailure)
        val response = Response.error<MobileSuccess<SyncStatusDto>>(502, "<html>edge error</html>".toResponseBody())
        val failure = MobileResultMapper.map(response) as MobileResult.ProtocolFailure
        assertEquals(502, failure.status); assertNull(failure.requestId)
    }

    @Test fun completeUtf8BudgetAndStrictJsonAreEnforced() {
        val overhead = "{\"value\":\"\"}".toByteArray().size
        val exact = "{\"value\":\"" + "x".repeat(MOBILE_RESPONSE_BYTES.toInt() - overhead) + "\"}"
        assertEquals(MOBILE_RESPONSE_BYTES, exact.toByteArray().size.toLong())
        assertEquals(MOBILE_RESPONSE_BYTES.toInt() - overhead, readMobileObject(exact.toResponseBody()).getString("value").length)
        assertTrue(runCatching { readMobileObject((exact + " ").toResponseBody()) }.exceptionOrNull() is MobileContractViolation)
        for (text in listOf("{'value':1}", "{value:1}", "{\"value\":01}", "{\"value\":NaN}",
                "{\"value\":1,}", "{\"value\":1}{}", "{\"value\":1,\"value\":2}", "/*x*/{}")) {
            assertTrue(text, runCatching { readMobileObject(text.toResponseBody()) }.exceptionOrNull() is MobileContractViolation)
        }
        assertTrue(runCatching { readMobileObject(byteArrayOf(0xc3.toByte(), 0x28).toResponseBody()) }
            .exceptionOrNull() is MobileContractViolation)
        val precise = readMobileObject("{\"cell\":9007199254740993.12345678}".toResponseBody())
        assertEquals("9007199254740993.12345678", precise.get("cell").toString())
    }
}
