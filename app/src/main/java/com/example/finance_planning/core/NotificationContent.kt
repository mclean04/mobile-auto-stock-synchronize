package com.example.finance_planning.core

import com.example.finance_planning.R
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object NotificationContent {
    fun shouldDisplay(event: JSONObject, now: Instant = Instant.now()): Boolean {
        val isTest = event.optJSONObject("sheet")?.optString("mode") == "TEST" &&
            event.optBoolean("is_current") && runCatching {
                Instant.parse(event.optString("test_push_until")).isAfter(now)
            }.getOrDefault(false)
        return event.optBoolean("local_only") || event.optBoolean("requires_review") || isTest
    }
    fun title(event: JSONObject): String {
        event.optString("title").takeIf { it.isNotBlank() }?.let { return it }
        val action = when (event.optString("action")) {
            "PLACE_ORDER" -> AppText.get(R.string.review_buy_sell_order)
            "CANCEL_ORDER" -> AppText.get(R.string.review_order_cancellation)
            "WITHDRAW_PLAN" -> AppText.get(R.string.plan_withdrawn)
            else -> AppText.get(R.string.plan_update)
        }
        val symbol = event.optString("symbol").takeIf { it.isNotBlank() }
        val prefix = if (event.optJSONObject("sheet")?.optString("mode") == "TEST") AppText.get(R.string.test) else ""
        return prefix + action + (symbol?.let { AppText.get(R.string.notification_symbol_suffix, it) } ?: "")
    }
    fun body(event: JSONObject): String = event.optString("body").takeIf { it.isNotBlank() }
        ?: event.optString("reason").takeIf { it.isNotBlank() }
        ?: AppText.get(R.string.open_the_notification_to_view_the_plan_update)
    fun status(event: JSONObject): String = when {
        event.optBoolean("local_only") -> AppText.get(R.string.firebase_notification)
        event.has("is_current") && !event.optBoolean("is_current") -> AppText.get(R.string.a_newer_update_is_available)
        expired(event) -> AppText.get(R.string.no_longer_valid)
        event.optJSONObject("sheet")?.optString("mode") == "TEST" -> AppText.get(R.string.test_notification)
        event.optBoolean("requires_review") -> AppText.get(R.string.review_required)
        else -> AppText.get(R.string.plan_information)
    }
    private fun expired(event: JSONObject) = runCatching {
        Instant.parse(event.getString("expires_at")).isBefore(Instant.now())
    }.getOrDefault(false)
    fun time(value: String): String = runCatching {
        DateTimeFormatter.ofPattern(AppText.get(R.string.date_time_pattern), AppText.locale).withZone(ZoneId.systemDefault())
            .format(Instant.parse(value))
    }.getOrDefault(value)
}
