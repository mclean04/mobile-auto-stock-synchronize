package com.example.finance_planning

import com.example.finance_planning.core.NotificationContent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotificationContentTest {
    @org.junit.Before fun initializeTextResources() { TestText.install() }
    @Test fun cancellationExplainsActionAndReasonWithoutTechnicalIds() {
        val event = JSONObject().put("action", "CANCEL_ORDER").put("symbol", "FPT")
            .put("event_id", "internal-id").put("reason", "Giá vượt giới hạn kế hoạch")
            .put("requires_review", true).put("expires_at", "2099-01-01T00:00:00Z")
        assertEquals("Nhắc xem xét hủy lệnh • FPT", NotificationContent.title(event))
        assertEquals("Giá vượt giới hạn kế hoạch", NotificationContent.body(event))
        assertEquals("Cần xem xét", NotificationContent.status(event))
        assertFalse(NotificationContent.title(event).contains("internal-id"))
    }
    @Test fun expiredAndSupersededEventsAreNotPresentedAsCurrentInstructions() {
        val event = JSONObject().put("requires_review", true).put("is_current", true)
            .put("expires_at", "2020-01-01T00:00:00Z")
        assertEquals("Đã hết hiệu lực", NotificationContent.status(event))
        event.put("is_current", false)
        assertEquals("Đã có cập nhật mới hơn", NotificationContent.status(event))
    }
    @Test fun consoleMessageUsesItsActualContent() {
        val event = JSONObject().put("local_only", true).put("title", "Thông báo thử")
            .put("body", "Kiểm tra đường truyền FCM")
        assertEquals("Thông báo thử", NotificationContent.title(event))
        assertEquals("Kiểm tra đường truyền FCM", NotificationContent.body(event))
        assertEquals("Thông báo Firebase", NotificationContent.status(event))
    }
    @Test fun onlyCurrentEligiblePlanningEventsTriggerUserAlerts() {
        val now = java.time.Instant.parse("2026-09-17T12:00:00Z")
        val test = JSONObject().put("sheet", JSONObject().put("mode", "TEST"))
            .put("is_current", true).put("test_push_until", "2026-09-17T12:01:00Z")
        assertTrue(NotificationContent.shouldDisplay(test, now))
        test.put("is_current", false)
        assertFalse(NotificationContent.shouldDisplay(test, now))
        test.put("is_current", true).put("test_push_until", "2026-09-17T11:59:00Z")
        assertFalse(NotificationContent.shouldDisplay(test, now))
        assertFalse(NotificationContent.shouldDisplay(JSONObject(), now))
        assertTrue(NotificationContent.shouldDisplay(JSONObject().put("local_only", true), now))
        assertTrue(NotificationContent.shouldDisplay(JSONObject().put("requires_review", true), now))
    }
}
