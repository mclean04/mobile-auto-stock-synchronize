package com.example.finance_planning

import com.example.finance_planning.core.NotificationContent
import com.example.finance_planning.core.OrderContent
import com.example.finance_planning.network.HttpFailure
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class LocalizationTest {
    @Test fun englishCoversEveryDefaultStringWithIdenticalFormatArguments() {
        val vi = TestText.strings("vi")
        val en = TestText.strings("en")
        assertEquals(vi.keys, en.keys)
        val placeholders = Regex("%\\d+\\$[a-z]")
        vi.forEach { (name, value) ->
            assertEquals(name, placeholders.findAll(value).map { it.value }.sorted().toList(),
                placeholders.findAll(en.getValue(name)).map { it.value }.sorted().toList())
        }
    }
    @Test fun generatedNotificationsAndErrorsUseEnglishWhileRemoteContentIsPreserved() {
        TestText.install("en")
        val event = JSONObject().put("action", "CANCEL_ORDER").put("symbol", "FPT")
            .put("reason", "Nội dung nguyên bản từ backend").put("requires_review", true)
        assertEquals("Review order cancellation • FPT", NotificationContent.title(event))
        assertEquals("Review required", NotificationContent.status(event))
        assertEquals("Nội dung nguyên bản từ backend", NotificationContent.body(event))
        assertEquals("The server returned HTTP error 503.", HttpFailure(503).safe().safeMessage)
        assertEquals("Partially filled", OrderContent.status(JSONObject().put("status", "partiallyFilled")))
        assertTrue(OrderContent.money(BigDecimal("1250000")).contains("1,250,000"))
    }
}
