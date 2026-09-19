package com.example.finance_planning

import com.example.finance_planning.network.PlacedOrderReport
import com.example.finance_planning.network.TradeDraft
import com.example.finance_planning.core.PlanningIntent
import com.example.finance_planning.core.PreflightAuthorization
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlacedOrderReportTest {
    private val draft = TradeDraft("FPT", "NB", 100, 120000, 1)
    private val at = Instant.parse("2026-09-19T12:15:30Z")

    @Test fun minimalBrokerResponseProducesStrictSandboxPayload() {
        val payload = PlacedOrderReport.payload("25d991f9-7813-4505-91ec-038910176310",
            "d5f91fd9-5bf5-450a-b7a6-0b59f44f4ca3", "sandbox", "000123",
            draft, JSONObject().put("id", "596"), at)
        assertEquals("sandbox", payload.getString("environment"))
        val order = payload.getJSONObject("order")
        assertEquals("596", order.getString("order_id"))
        assertEquals("BUY", order.getString("side"))
        assertEquals("100", order.getString("quantity"))
        assertEquals("120000", order.getString("price_vnd"))
        assertEquals("0", order.getString("filled_quantity"))
        assertEquals("PENDING", order.getString("status"))
        assertTrue(order.isNull("average_fill_price_vnd"))
        assertEquals(at.toString(), order.getString("created_at"))
    }

    @Test fun completeBrokerResponsePreservesBrokerOrderDetails() {
        val raw = JSONObject("""{"id":"42","symbol":"HPG","side":"NS","orderType":"LO",
            "quantity":200,"fillQuantity":100,"price":28500,"averagePrice":28400,
            "orderStatus":"PartiallyFilled","marketType":"STOCK","orderCategory":"NORMAL",
            "createdDate":"2026-09-19T18:00:00+07:00","modifiedDate":"2026-09-19T18:01:00+07:00"}""")
        val sell = TradeDraft("HPG", "NS", 200, 28500, 2)
        val payload = PlacedOrderReport.payload("0f24852c-87b5-461d-9330-6cd2aac0e674",
            "ff0e4f8e-2351-45fb-b6f9-63ed42536fd4", "production", "00999", sell,
            JSONObject().put("data", raw), at)
        val order = payload.getJSONObject("order")
        assertEquals("production", payload.getString("environment"))
        assertEquals("SELL", order.getString("side"))
        assertEquals("100", order.getString("filled_quantity"))
        assertEquals("28400", order.getString("average_fill_price_vnd"))
        assertEquals("2026-09-19T11:00:00Z", order.getString("created_at"))
        assertFalse(payload.has("api_key"))
        assertFalse(payload.toString().contains("secret", ignoreCase = true))
    }

    @Test fun unsupportedEnvironmentIsRejectedBeforeSending() {
        assertThrows(IllegalArgumentException::class.java) {
            PlacedOrderReport.payload("request", "device", "paper", "account", draft,
                JSONObject().put("id", "1"), at)
        }
    }

    @Test fun typedReportBindsIntentVersionEnvironmentAccountAndPreflight() {
        val intentJson = JSONObject().put("contract_version", "2.0")
            .put("plan_id", "59c827db-79fa-4a56-943d-291831f28f51")
            .put("intent_id", "792f0b94-ad11-492b-b375-91916fa4ec68")
            .put("version", 3).put("authoring_state", "APPROVED").put("execution_state", "NOT_STARTED")
            .put("environment", "sandbox").put("recipient_uid", "uid").put("account", "000123")
            .put("symbol", "FPT").put("side", "BUY").put("quantity", "100")
            .put("limit_price_vnd", "120000").put("scheduled_at", "2026-10-01T09:00:00+07:00")
            .put("window_starts_at", "2026-10-01T09:00:00+07:00")
            .put("window_ends_at", "2026-10-01T14:30:00+07:00")
            .put("eligibility", JSONObject().put("eligible", true).put("reasons", JSONArray()))
        val preflightJson = JSONObject().put("contract_version", "2.0")
            .put("preflight_id", "c7e13b2f-0512-4947-b7ad-d0d9902a4d62")
            .put("intent_id", intentJson.getString("intent_id")).put("current_version", 3)
            .put("server_time", "2026-10-01T09:10:00+07:00")
            .put("preflight_expires_at", "2026-10-01T09:15:00+07:00")
            .put("eligibility", JSONObject().put("eligible", true).put("reasons", JSONArray()))
        val intent = PlanningIntent.parse(intentJson)
        val payload = PlacedOrderReport.typedPayload("25d991f9-7813-4505-91ec-038910176310",
            "d5f91fd9-5bf5-450a-b7a6-0b59f44f4ca3", intent,
            PreflightAuthorization.parse(preflightJson, intent), "000123", draft,
            JSONObject().put("id", "596"), at)
        assertEquals("2.0", payload.getString("contract_version"))
        assertEquals(3, payload.getInt("expected_version"))
        assertEquals("sandbox", payload.getString("environment"))
        assertEquals("000123", payload.getString("account"))
        assertEquals("596", payload.getJSONObject("order").getString("order_id"))
        assertFalse(payload.toString().contains("firebase-user"))
    }
}
