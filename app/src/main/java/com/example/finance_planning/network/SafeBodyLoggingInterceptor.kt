package com.example.finance_planning.network

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** BODY diagnostics with deny-by-default value redaction; never logs headers or raw text. */
class SafeBodyLoggingInterceptor(private val log: (String) -> Unit) : Interceptor {
    companion object { const val MAX_BODY = 8192L }
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val uri = request.url.toUri()
        val method = request.method.takeIf { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD") } ?: "other"
        val url = ApiDiagnostics.dnseUrl(uri)
        val started = System.nanoTime()
        log("--> $method $url")
        HttpLogFormat.headers(request.headers.map { it.first to it.second }, log)
        // DNSE's Retrofit service is GET-only; never replay an arbitrary request body for logging.
        log("--> END $method${if (request.body == null) "" else " (body omitted)"}")
        try {
            val response = chain.proceed(request)
            try {
                val preview = response.peekBody(MAX_BODY + 1).use { it.bytes() }
                val raw = if (preview.size <= MAX_BODY) String(preview, Charsets.UTF_8) else null
                val code = if (!response.isSuccessful) ApiDiagnostics.dnseCode(raw) else null
                log("<-- ${response.code} $url (${(System.nanoTime() - started) / 1_000_000}ms)")
                HttpLogFormat.headers(response.headers.map { it.first to it.second }, log)
                if (code != null) log("DNSE-error-code: $code")
                if (raw != null) HttpLogFormat.body(raw, log) else log("(body omitted: exceeds 8192 bytes)")
                log("<-- END HTTP (${if (raw == null) ">8192" else preview.size.toString()}-byte body; redacted)")
            } catch (e: java.io.IOException) {
                response.close()
                throw e
            }
            return response
        } catch (e: java.io.IOException) {
            log("<-- HTTP FAILED: ${ApiDiagnostics.failure(e)} $url")
            throw e
        }
    }
}

object SafeJsonBody {
    private val keys = setOf("accounts", "id", "accountNo", "data", "items", "orders", "positions",
        "executions", "stock", "derivative", "bond", "egg", "availableCash", "totalCash", "cash",
        "balance", "balances", "buyingPower", "purchasingPower", "quantity", "fillQuantity",
        "filledQuantity", "price", "averagePrice", "orderId", "symbol", "side", "orderType",
        "orderStatus", "status", "code", "message", "createdDate", "modifiedDate", "createdAt",
        "updatedAt", "marketType", "orderCategory", "total", "pageIndex", "pageSize", "hasNext",
        "depositInterest", "totalDebt", "depositFeeAmount", "secureAmount", "orderSecured",
        "withdrawableCash", "cashDividendReceiving", "totalValue", "remainSecure", "usedSecure",
        "batch_id", "device_id", "fcm_token", "state", "database", "orders_count", "executions_count",
        "positions_count", "balances_count", "account", "cash_vnd", "buying_power_vnd", "updated_at",
        "order_id", "execution_id", "position_id", "schema_version", "detail", "next_cursor",
        "role", "owner", "sources", "notifications", "planning", "requires_review", "event_id")
    fun render(raw: String): String {
        if (raw.isBlank()) return "<empty>"
        if (raw.length > 8192) return "<omitted: large body>"
        // Reject excessive nesting before JSONTokener can recurse into untrusted input.
        var depth = 0
        var quoted = false
        var escape = false
        for (c in raw) {
            if (escape) { escape = false; continue }
            if (quoted && c == '\\') { escape = true; continue }
            if (c == '"') { quoted = !quoted; continue }
            if (!quoted) {
                if (c == '{' || c == '[') { depth++; if (depth > 12) return "<omitted: deep body>" }
                if (c == '}' || c == ']') depth--
            }
        }
        return try {
            val parsed = JSONTokener(raw).nextValue()
            if (parsed !is JSONObject && parsed !is JSONArray) "<omitted: non-JSON body>"
            else clean(parsed, 0).toString().take(8192)
        } catch (_: Exception) { "<omitted: invalid JSON body>" }
    }
    private fun clean(value: Any?, depth: Int): Any {
        if (depth > 6) return "<omitted: depth>"
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> JSONObject().apply {
                val names = value.keys().asSequence().toList()
                names.filter(keys::contains).take(40).forEach { put(it, clean(value.opt(it), depth + 1)) }
                if (names.any { it !in keys }) put("_other_fields", "<redacted>")
            }
            is JSONArray -> JSONArray().apply {
                for (i in 0 until minOf(value.length(), 3)) put(clean(value.opt(i), depth + 1))
                if (value.length() > 3) put("<remaining items omitted>")
            }
            is Number -> "<number>"
            is Boolean -> "<boolean>"
            else -> "<redacted>"
        }
    }
}
