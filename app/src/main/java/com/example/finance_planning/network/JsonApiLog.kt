package com.example.finance_planning.network

import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject
import org.json.JSONTokener

/** JSON log envelopes, never HTTP payloads. Rendering cannot mutate the wire request. */
object JsonApiLog {
    fun request(uri: URI, method: String, body: String?): String = JSONObject()
        .put("method", method.takeIf { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD") } ?: "other")
        .put("url", endpoint(uri)).put("query", query(uri))
        .put("body", redactedBody(body)).toString(2)
    fun response(uri: URI, status: Int, body: String?): String = JSONObject()
        .put("url", endpoint(uri)).put("status", status)
        .put("body", redactedBody(body)).toString(2)
    private fun endpoint(uri: URI): String = when {
        ApiDiagnostics.service(uri).startsWith("dnse-") -> ApiDiagnostics.dnseUrl(uri).substringBefore('?')
        ApiDiagnostics.service(uri) == "planning-backend" -> "https://${uri.host}${ApiDiagnostics.route(uri)}"
        else -> "<redacted-url>"
    }
    private fun query(uri: URI): JSONObject = JSONObject().apply {
        for (entry in uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)) {
            val parts = entry.split('=', limit = 2)
            val key = parts[0]
            val value = runCatching { URLDecoder.decode(parts.getOrNull(1).orEmpty(), "UTF-8") }.getOrDefault("")
            when (key) {
                "pageIndex", "pageSize", "limit" -> put(key, if (value.matches(Regex("[0-9]{1,6}"))) value.toInt() else "<redacted>")
                "from", "to" -> put(key, if (runCatching { java.time.LocalDate.parse(value) }.isSuccess) value else "<redacted>")
                "marketType" -> put(key, if (value in setOf("STOCK", "DERIVATIVE")) value else "<redacted>")
                "orderCategory" -> put(key, if (value in setOf("NORMAL", "STOP")) value else "<redacted>")
                "cursor", "source" -> put(key, "<redacted>")
                else -> put("_other_parameters", "<redacted>")
            }
        }
    }
    private fun redactedBody(raw: String?): Any {
        if (raw.isNullOrBlank()) return JSONObject.NULL
        val safe = SafeJsonBody.render(raw)
        return if (safe.startsWith('{') || safe.startsWith('['))
            runCatching { JSONTokener(safe).nextValue() }.getOrDefault("<omitted>") else safe
    }
    fun emit(kind: String, id: String, json: String, log: (String) -> Unit) {
        json.chunked(2500).forEachIndexed { part, text -> log("$kind JSON [id=$id part=$part]\n$text") }
    }
}
