package com.example.finance_planning.core

import com.example.finance_planning.R
import org.json.JSONObject
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale

object OrderContent {
    private fun statusCode(p: JSONObject) = p.optString("status").replace("_", "").uppercase(Locale.ROOT)
    fun payload(row: JSONObject) = row.optJSONObject("payload") ?: row
    fun waiting(row: JSONObject): Boolean {
        val p = payload(row)
        return statusCode(p) in setOf("NEW", "PENDING", "OPEN", "ACTIVE", "PARTIALLYFILLED", "PENDINGNEW", "WAITING", "ACCEPTED", "TRIGGERPENDING") &&
            (number(p, "quantity") == null || number(p, "filled_quantity") == null || number(p, "filled_quantity")!! < number(p, "quantity")!!)
    }
    fun planWaiting(p: JSONObject, now: Instant = Instant.now()): Boolean =
        !p.optBoolean("local_only") && p.optString("action") == "PLACE_ORDER" &&
            (!p.has("is_current") || p.optBoolean("is_current")) &&
            p.optJSONObject("sheet")?.optString("mode") != "TEST" &&
            runCatching { Instant.parse(p.getString("due_at")) > now }.getOrDefault(false) &&
            runCatching { Instant.parse(p.getString("expires_at")) > now }.getOrDefault(true)
    fun number(p: JSONObject, key: String): BigDecimal? = p.optString(key).toBigDecimalOrNull()
    fun money(n: BigDecimal?): String = n?.let { NumberFormat.getNumberInstance(AppText.locale).format(it) + AppText.get(R.string.currency_vnd) } ?: AppText.get(R.string.no_data_available)
    fun quantity(p: JSONObject, key: String): String = number(p, key)?.stripTrailingZeros()?.toPlainString() ?: AppText.get(R.string.no_data_available)
    fun status(p: JSONObject): String = when(statusCode(p)) {
        "FILLED", "FULLYFILLED" -> AppText.get(R.string.fully_filled)
        "PARTIALLYFILLED" -> AppText.get(R.string.partially_filled)
        "CANCELED", "CANCELLED" -> AppText.get(R.string.cancelled)
        "REJECTED" -> AppText.get(R.string.rejected)
        "EXPIRED", "DONEFORDAY" -> AppText.get(R.string.expired)
        "NEW", "OPEN", "ACTIVE", "ACCEPTED", "PENDING", "PENDINGNEW", "WAITING", "TRIGGERPENDING" -> AppText.get(R.string.awaiting_execution)
        else -> AppText.get(R.string.dnse_status, p.optString("status", AppText.get(R.string.not_verified)))
    }
}
