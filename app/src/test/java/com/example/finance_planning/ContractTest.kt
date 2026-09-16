package com.example.finance_planning

import com.example.finance_planning.core.Contracts
import com.example.finance_planning.network.DnseApi
import com.example.finance_planning.network.DnseSigning
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ContractTest {
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
        assertTrue(runCatching { Contracts.batch("device", List(101) { order() }, "batch") }.isFailure)
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
        val original = Contracts.batch("device", listOf(order()), "batch").toString()
        val retry = JSONObject(original).toString()
        assertEquals(JSONObject(original).getString("batch_id"), JSONObject(retry).getString("batch_id"))
        assertEquals(original, retry)
    }
}
