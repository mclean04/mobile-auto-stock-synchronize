package com.example.finance_planning.network

import com.example.finance_planning.core.Contracts
import org.json.JSONObject
import java.net.URI

/** Logs only predefined labels; never interpolate URLs, headers, bodies or exception messages. */
object ApiDiagnostics {
    fun service(uri: URI): String = when (uri.host) {
        "openapi.dnse.com.vn" -> "dnse-production"
        "sb-openapi.dnse.com.vn" -> "dnse-sandbox"
        URI(Contracts.BACKEND).host -> "planning-backend"
        else -> "other"
    }
    private val routes = listOf(
        "/accounts", "/accounts/{id}/balances", "/accounts/{id}/positions",
        "/accounts/{id}/orders/history", "/accounts/{id}/orders",
        "/accounts/{id}/orders/{id}", "/accounts/{id}/executions/{id}",
        "/health", "/v1/admin/sources", "/v1/admin/sources/{id}/records",
        "/v1/sync/status", "/v1/sync/retry", "/v1/sync/batches", "/v1/sync/batches/{id}",
        "/v1/planning/latest", "/v1/planning/import", "/v1/sheets/reconcile",
        "/v1/orders", "/v1/orders/{id}/{id}", "/v1/devices/{id}",
        "/v1/notifications", "/v1/notifications/{id}", "/v1/notifications/{id}/receipts",
        "/v1/notification-plans/{id}", "/v1/notification-preview"
    )
    fun route(uri: URI): String {
        if (service(uri) == "other") return "/[redacted]"
        val parts = uri.rawPath.orEmpty().split('/')
        return routes.firstOrNull { template ->
            val expected = template.split('/')
            expected.size == parts.size && expected.zip(parts).all { (a, b) ->
                if (a == "{id}") b.isNotEmpty() else a == b
            }
        } ?: "/[redacted]"
    }
    /** Explicit debug URL: account/order IDs visible; unknown query parameters never emitted. */
    fun dnseUrl(uri: URI): String {
        if (!service(uri).startsWith("dnse-") || route(uri) == "/[redacted]") return "<redacted-url>"
        val query = uri.rawQuery?.split('&')?.map { entry ->
            val parts = entry.split('=', limit = 2)
            val name = parts[0]
            val value = parts.getOrNull(1).orEmpty()
            val allowed = when (name) {
                "pageIndex", "pageSize" -> value.matches(Regex("[0-9]{1,6}"))
                "from", "to" -> runCatching { java.time.LocalDate.parse(value) }.isSuccess
                "marketType" -> value in setOf("STOCK", "DERIVATIVE")
                "orderCategory" -> value in setOf("NORMAL", "STOP")
                else -> false
            }
            if (allowed) "$name=$value" else "redacted=REDACTED"
        }?.joinToString("&")
        return "https://${uri.host}${uri.rawPath}" + if (query.isNullOrEmpty()) "" else "?$query"
    }
    private val dnseCodes = setOf("OA-400", "OA-401", "OA-403", "OA-404", "OA-405",
        "OA-422", "OA-429", "OA-500", "OA-503", "FORBIDDEN", "INPUT_MISSING",
        "INPUT_INVALID", "INPUT_FORMAT_INVALID", "ACCOUNT_MISSING", "RESOURCE_NOT_FOUND",
        "INVALID_MARKET_TYPE", "TIMEOUT", "SYSTEM_ERROR", "REMOTE_SERVER_ERROR", "THIRD_PARTY_ERROR")
    fun dnseCode(raw: String?): String? = try {
        val body = JSONObject(raw ?: "{}")
        // Legacy gateway messages are translated to fixed codes, never copied into logs.
        when (body.optString("message").lowercase(java.util.Locale.ROOT).trim()) {
            "invalid api key" -> "invalid_api_key"
            "invalid signature" -> "invalid_signature"
            "authorization field missing, malformed or invalid" -> "invalid_authorization"
            else -> body.optString("code").takeIf(dnseCodes::contains)
        }
    } catch (_: Exception) { null }
    fun failure(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "timeout"
        is java.net.UnknownHostException -> "dns"
        is javax.net.ssl.SSLException -> "tls"
        is java.io.IOException -> "network_io"
        is HttpFailure -> "http_error"
        else -> "local_error"
    }
}
