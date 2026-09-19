package com.example.finance_planning

import com.example.finance_planning.network.PlacedOrderReport
import com.example.finance_planning.network.TradeDraft
import org.json.JSONObject
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
}
