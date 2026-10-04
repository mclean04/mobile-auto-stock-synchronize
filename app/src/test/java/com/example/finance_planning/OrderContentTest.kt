package com.example.finance_planning

import com.example.finance_planning.core.OrderContent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.math.BigDecimal

class OrderContentTest {
    @org.junit.Before fun initializeTextResources() { TestText.install() }
    @Test fun partiallyFilledBrokerOrdersRemainInWaitingUntilComplete() {
        val p = JSONObject().put("status", "partiallyFilled").put("quantity", "100").put("filled_quantity", "40")
        assertTrue(OrderContent.waiting(p))
        assertEquals("Đã khớp một phần", OrderContent.status(p))
        p.put("filled_quantity", "100")
        assertFalse(OrderContent.waiting(p))
        p.put("status", "cancelled").put("filled_quantity", "40")
        assertFalse(OrderContent.waiting(p))
    }
    @Test fun pastOrWithdrawnPlansNeverAppearAsUpcomingTrades() {
        val p = JSONObject().put("action", "PLACE_ORDER").put("due_at", "2030-01-01T00:00:00Z").put("is_current", true)
        val now = Instant.parse("2029-01-01T00:00:00Z")
        assertTrue(OrderContent.planWaiting(p, now))
        p.put("is_current", false)
        assertFalse(OrderContent.planWaiting(p, now))
        p.put("is_current", true).put("due_at", "2028-01-01T00:00:00Z")
        assertFalse(OrderContent.planWaiting(p, now))
    }
    @Test fun missingPriceIsNotInventedAsZero() {
        assertNull(OrderContent.number(JSONObject().put("price_vnd", JSONObject.NULL), "price_vnd"))
        assertEquals("Chưa có dữ liệu", OrderContent.money(null))
        assertTrue(OrderContent.money(BigDecimal("1250000")).contains("1.250.000"))
    }
}
