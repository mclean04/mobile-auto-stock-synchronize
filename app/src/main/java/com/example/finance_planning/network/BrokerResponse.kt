package com.example.finance_planning.network

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Sandbox diagnostics only. Never records headers or unredacted trading tokens. */
data class BrokerResponse(val method: String, val path: String, val status: Int, val body: String)

object BrokerResponseRedaction {
    fun body(raw: String, sensitive: List<String>): String {
        fun text(value: String): String = sensitive.filter { it.isNotBlank() }
            .fold(value) { result, secret -> result.replace(secret, "[REDACTED]") }
        fun scrub(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().also { clean -> value.keys().forEach { key ->
                val privateField = Regex("(?i).*?(token|secret|api.?key|password|passcode|otp|signature).*?").matches(key)
                clean.put(key, if (privateField) "[REDACTED]" else scrub(value.opt(key)))
            } }
            is JSONArray -> JSONArray().also { clean -> for (i in 0 until value.length()) clean.put(scrub(value.opt(i))) }
            is String -> text(value)
            else -> value
        }
        // Unknown non-JSON responses may echo headers: do not expose them.
        return runCatching { when (val result = scrub(JSONTokener(raw).nextValue())) {
            is JSONObject -> result.toString(2)
            is JSONArray -> result.toString(2)
            else -> "[Non-JSON response omitted]"
        } }.getOrDefault(if (raw.isBlank()) "{}" else "[Non-JSON response omitted]")
    }
}
