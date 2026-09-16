package com.example.finance_planning.network

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicLong

/** BODY diagnostics with deny-by-default value redaction; never logs headers or raw text. */
class SafeBodyLoggingInterceptor(private val log: (String) -> Unit) : Interceptor {
    companion object { private val ids = AtomicLong(); const val MAX_BODY = 8192L }
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val uri = request.url.toUri()
        val id = ids.incrementAndGet()
        val label = "id=$id service=${ApiDiagnostics.service(uri)} method=${if (request.method == "GET") "GET" else "other"} route=${ApiDiagnostics.route(uri)} url=${ApiDiagnostics.dnseUrl(uri)}"
        val method = if (request.method == "GET") "GET" else "other"
        val url = ApiDiagnostics.dnseUrl(uri)
        val started = System.nanoTime()
        // DNSE is GET-only: never serialize/replay an unknown request body just for logging.
        log("--> $method $url [id=$id]")
        JsonApiLog.emit("REQUEST", id.toString(), JsonApiLog.request(uri, method, null), log)
        log("--> END $method (${if (request.body == null) "0-byte body" else "body omitted"}) [id=$id]")
        try {
            val response = chain.proceed(request)
            try {
                val preview = response.peekBody(MAX_BODY + 1).use { it.bytes() }
                val raw = if (preview.size <= MAX_BODY) String(preview, Charsets.UTF_8) else null
                val code = if (!response.isSuccessful) ApiDiagnostics.dnseCode(raw) else null
                val date = response.headers.getDate("Date")?.time
                val skew = date?.let { (System.currentTimeMillis() - it) / 1000 }
                log("<-- ${response.code} $url (${(System.nanoTime() - started) / 1_000_000}ms) [id=$id code=${code ?: "none"} device_minus_server_seconds=${skew ?: "unknown"}]")
                JsonApiLog.emit("RESPONSE", id.toString(),
                    JsonApiLog.response(uri, response.code, raw ?: "<body exceeds limit>"), log)
                log("<-- END HTTP (redacted body) [id=$id]")
            } catch (e: java.io.IOException) {
                response.close()
                throw e
            }
            return response
        } catch (e: java.io.IOException) {
            log("<-- HTTP FAILED: $label outcome=${ApiDiagnostics.failure(e)} elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
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
