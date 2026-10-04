package com.example.finance_planning

import com.example.finance_planning.core.Contracts
import com.example.finance_planning.core.PlanningSourceContext
import com.example.finance_planning.network.DnseApi
import com.example.finance_planning.network.DnseSigning
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ContractTest {
    private val source = PlanningSourceContext("source-sheet-0001", 7)
    @org.junit.Before fun initializeTextResources() { TestText.install() }
    @Test fun signatureMatchesExistingPythonBackendVector() {
        val signature = DnseSigning.signature("local-test-key", "local-test-secret", "/accounts",
            "Fri, 15 May 2026 07:11:30 +0000", "26c4b530cf12427d95bf691e39aa8d74")
        assertTrue(signature.contains("signature=\"aQW%2BRxjzl2ahPCBLPhtTfLRIhLa2AClV6RAHncfGjRg%3D\""))
    }
    @Test(expected = IllegalArgumentException::class) fun signatureCannotIncludeQuery() {
        DnseSigning.signature("key", "secret", "/accounts?bad=1", "date", "0".repeat(32))
    }
    private fun order() = JSONObject("""{"id":"42","symbol":"TEST","side":"NB","orderType":"LO",
        "quantity":"100","fillQuantity":"20","price":"12.345","averagePrice":"12.34",
        "orderStatus":"partiallyFilled","createdDate":"2026-09-15T09:00:00+07:00",
        "modifiedDate":"2026-09-15T09:10:00+07:00"}""")
    @Test fun priceConversionUsesExactDecimalAndSourceTime() {
        val out = DnseApi.normalizeOrder("account", order(), BigDecimal("1000"))
        assertEquals("12345", out.getString("price_vnd"))
        assertEquals("12340", out.getString("average_fill_price_vnd"))
        assertEquals("2026-09-15T02:10:00Z", out.getString("updated_at"))
        assertEquals("BUY", out.getString("side"))
        assertFalse(out.has("api_key"))
    }
    @Test fun missingTimestampCannotBecomeSyncTime() {
        val raw = order().apply { remove("modifiedDate") }
        assertTrue(runCatching { DnseApi.normalizeOrder("account", raw, BigDecimal.ONE) }.isFailure)
    }
    @Test fun invalidSideAndOverfillAreRejected() {
        assertTrue(runCatching { DnseApi.normalizeOrder("account", order().put("side", "???"), BigDecimal.ONE) }.isFailure)
        assertTrue(runCatching { DnseApi.normalizeOrder("account", order().put("fillQuantity", 101), BigDecimal.ONE) }.isFailure)
    }
    @Test fun batchNeverExceedsFirestoreLimit() {
        assertTrue(runCatching { Contracts.batch("device", List(101) { order() }, emptyList(), emptyList(), emptyList(), "batch", source) }.isFailure)
    }
    @Test fun executionPositionAndBalanceMapToBackendContract() {
        val execution = JSONObject("""{"id":"fill-1","orderId":"42","quantity":"20",
            "price":"12.34","fee":"0.02","executedAt":"2026-09-15T09:05:00+07:00"}""")
        val fill = DnseApi.normalizeExecution("account", "42", execution, BigDecimal("1000"))
        assertEquals("12340", fill.getString("price_vnd"))
        assertEquals("20", fill.getString("fee_vnd"))
        val observed = java.time.Instant.parse("2026-09-15T02:10:00Z")
        val position = DnseApi.normalizePosition("account", JSONObject(
            """{"symbol":"TEST","quantity":"100","tradeQuantity":"80","costPrice":"12.3"}"""),
            BigDecimal("1000"), observed)
        assertEquals("TEST", position.getString("position_id"))
        assertEquals("12300", position.getString("average_price_vnd"))
        val balance = DnseApi.normalizeBalance("account", JSONObject(
            """{"cash":"1000","buyingPower":"900"}"""), BigDecimal.ONE, observed)
        assertEquals("1000", balance.getString("cash_vnd"))
        val batch = Contracts.batch("00000000-0000-4000-8000-000000000001", emptyList(),
            listOf(fill), listOf(position), listOf(balance), "00000000-0000-4000-8000-000000000002", source)
        assertEquals(1, batch.getJSONArray("executions").length())
        assertEquals(1, batch.getJSONArray("positions").length())
        assertEquals(1, batch.getJSONArray("balances").length())
    }
    @Test fun stockBalanceUsesNestedCashNotTotalOrDerivativeAndNotPriceUnits() {
        val raw = JSONObject("""{"stock":{"availableCash":123456,"totalCash":999999},
            "derivative":{"remainSecure":888888},"cash":777777}""")
        val out = DnseApi.normalizeBalance("test", raw, BigDecimal("1000"), java.time.Instant.EPOCH)
        assertEquals("123456", out.getString("cash_vnd"))
        assertTrue(out.isNull("buying_power_vnd"))
    }
    @Test fun missingStockCashCannotBecomeZeroOrTotalCash() {
        for (raw in listOf("""{"stock":{"totalCash":123}}""", """{"stock":null,"cash":123}""")) {
            assertTrue(runCatching { DnseApi.normalizeBalance("test", JSONObject(raw),
                BigDecimal.ONE, java.time.Instant.EPOCH) }.isFailure)
        }
        val zero = DnseApi.normalizeBalance("test", JSONObject("""{"stock":{"availableCash":0}}"""),
            BigDecimal.ONE, java.time.Instant.EPOCH)
        assertEquals("0", zero.getString("cash_vnd"))
    }
    @Test fun executionWithoutFeeUsesExplicitNull() {
        val execution = JSONObject("""{"id":"fill-1","quantity":"1","price":"10",
            "executedAt":"2026-09-15T09:05:00+07:00"}""")
        val fill = DnseApi.normalizeExecution("account", "42", execution, BigDecimal.ONE)
        assertTrue(fill.isNull("fee_vnd"))
    }
    @Test fun reminderNeedsEveryCanonicalValidityFlag() {
        val event = JSONObject().put("requires_review", true).put("is_current", true)
            .put("revision_is_current", false).put("broker_action_executed", false)
        assertFalse(Contracts.mayReview(event))
        event.put("revision_is_current", true)
        assertTrue(Contracts.mayReview(event))
        event.put("is_current", false)
        assertFalse(Contracts.mayReview(event))
    }
    @Test fun changingPayloadDoesNotMutatePreviouslySerializedBatch() {
        val original = Contracts.batch("device", listOf(order()), emptyList(), emptyList(), emptyList(), "batch", source).toString()
        val retry = JSONObject(original).toString()
        assertEquals(JSONObject(original).getString("batch_id"), JSONObject(retry).getString("batch_id"))
        assertEquals(7, JSONObject(retry).getJSONObject("source_context").getLong("source_generation"))
        assertEquals(original, retry)
    }
}
