package com.example.finance_planning

import com.example.finance_planning.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.example.finance_planning.network.QaNotificationTransport

class NotificationDeliveryTest {
    private val uid = "qa-firebase-uid"
    private val namespace = "qa-source-A0001:3"
    private val event = "3e0f2b9b-62f9-49f7-954b-26a716a96e40"
    private val device = "d506628f-c0b8-4d6a-9c21-612128341ede"
    private val config = QaNotificationConfig(uid, device, namespace, "private-test-bearer")

    private fun push(plan: String) = mapOf(
        "schema_version" to "1", "event_id" to event, "plan_id" to plan,
        "version" to "1", "type" to "PLANNING_REVIEW_REQUIRED", "target_uid" to uid,
        "test_marker" to "DEMO_ONLY", "notification_namespace" to namespace
    )

    @Test fun dailyAndMonthlyQaPushesSelectOnlyPinnedQaRoute() {
        for (plan in listOf("test-dnse-daily-20260922-1030", "test-dnse-monthly-20260922-1100")) {
            val route = NotificationDeliveryPolicy.push(push(plan), uid, config)!!
            assertEquals(com.example.finance_planning.core.NotificationEndpoint.QA, route.endpoint)
            assertEquals(plan, route.planId)
            assertEquals(namespace, route.namespace)
            assertFalse(route.json().toString().contains("bearer"))
            assertEquals(route, NotificationDelivery.parse(route.json()))
        }
    }

    @Test fun qaTransportAllowsOnlyFixedLoopbackNotificationRoutes() {
        val base = NotificationDeliveryPolicy.QA_BASE_URL
        assertTrue(QaNotificationTransport.allowlisted("$base/v1/notifications?limit=20", "GET"))
        assertTrue(QaNotificationTransport.allowlisted("$base/v1/notifications/$event", "GET"))
        assertTrue(QaNotificationTransport.allowlisted("$base/v1/notifications/$event/receipts", "POST"))
        assertTrue(QaNotificationTransport.allowlisted("$base/v1/devices/$device", "PUT"))
        assertFalse(QaNotificationTransport.allowlisted("https://attacker.example/v1/notifications/$event", "GET"))
        assertFalse(QaNotificationTransport.allowlisted("http://127.0.0.1:18767/v1/notifications/$event", "GET"))
        assertFalse(QaNotificationTransport.allowlisted("$base/v1/notifications", "POST"))
        assertFalse(QaNotificationTransport.allowlisted("$base/v1/orders", "GET"))
    }

    @Test fun qaPushRejectsArbitraryUrlCrossUidNamespaceMarkerAndUnknownFields() {
        assertNull(NotificationDeliveryPolicy.push(push("test-dnse-daily-20260922-1030") +
            ("url" to "https://attacker.example"), uid, config))
        assertNull(NotificationDeliveryPolicy.push(push("test-dnse-daily-20260922-1030") +
            ("target_uid" to "other"), uid, config))
        assertNull(NotificationDeliveryPolicy.push(push("test-dnse-daily-20260922-1030") +
            ("notification_namespace" to "qa-source-B0002:4"), uid, config))
        assertNull(NotificationDeliveryPolicy.push(push("test-dnse-daily-20260922-1030") +
            ("test_marker" to "LIVE"), uid, config))
        assertNull(NotificationDeliveryPolicy.push(push("test-other-20260922-1030"), uid, config))
        assertNull(NotificationDeliveryPolicy.push(push("test-dnse-daily-20260922-1030"), uid, null))
    }

    @Test fun configuredQaModeDoesNotFallThroughToProductionAndProductionBehaviorRemains() {
        val production = mapOf("schema_version" to "1", "event_id" to event,
            "target_uid" to uid, "type" to "PLANNING_REVIEW_REQUIRED")
        assertNull(NotificationDeliveryPolicy.push(production, uid, config))
        assertEquals(com.example.finance_planning.core.NotificationEndpoint.PRODUCTION,
            NotificationDeliveryPolicy.push(production, uid, null)!!.endpoint)
    }

    @Test fun canonicalQaEventMustMatchPushIdentityAndRemainTestOnly() {
        val route = NotificationDeliveryPolicy.push(push("test-dnse-monthly-20260922-1100"), uid, config)!!
        val canonical = JSONObject().put("event_id", event).put("recipient_uid", uid)
            .put("plan_id", route.planId).put("version", 1)
            .put("sheet", JSONObject().put("mode", "TEST"))
            .put("is_current", true).put("test_notification_visible", true)
            .put("notification_namespace", namespace)
        NotificationDeliveryPolicy.validateCanonical(canonical, route, true)
        for (bad in listOf(
            JSONObject(canonical.toString()).put("recipient_uid", "other"),
            JSONObject(canonical.toString()).put("notification_namespace", "qa-source-B0002:4"),
            JSONObject(canonical.toString()).put("plan_id", "test-dnse-daily-20260922-1030"),
            JSONObject(canonical.toString()).put("test_notification_visible", false)
        )) assertTrue(runCatching { NotificationDeliveryPolicy.validateCanonical(bad, route, true) }.isFailure)
        // OPENED may arrive after the display window; identity/TEST checks still apply.
        canonical.put("test_notification_visible", false)
        NotificationDeliveryPolicy.validateCanonical(canonical, route, false)
    }
}
