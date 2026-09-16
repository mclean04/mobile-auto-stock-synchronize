package com.example.finance_planning.network

/** OkHttp BODY layout, with credentials and untrusted header values removed. */
object HttpLogFormat {
    fun headers(headers: Iterable<Pair<String, String>>, log: (String) -> Unit) {
        for ((name, value) in headers) {
            val key = name.lowercase(java.util.Locale.ROOT)
            val safe = when (key) {
                "content-length" -> value.takeIf { it.matches(Regex("[0-9]{1,12}")) }
                "content-type", "accept" -> value.takeIf { it.matches(Regex("application/json(; ?charset=[Uu][Tt][Ff]-8)?")) }
                "content-encoding", "accept-encoding" -> value.takeIf { it in setOf("gzip", "br", "identity") }
                "connection" -> value.takeIf { it.lowercase() in setOf("keep-alive", "close") }
                "date" -> value.takeIf { runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME) }.isSuccess }
                "version" -> value.takeIf { runCatching { java.time.LocalDate.parse(it) }.isSuccess }
                "user-agent" -> value.takeIf { it.matches(Regex("okhttp/[0-9.]+")) }
                "host" -> value.takeIf { it in setOf("openapi.dnse.com.vn", "sb-openapi.dnse.com.vn", java.net.URI(com.example.finance_planning.core.Contracts.BACKEND).host) }
                else -> null
            }
            val safeName = key.takeIf { it in setOf("authorization", "x-api-key", "x-signature", "x-firebase-appcheck", "cookie", "set-cookie", "content-length", "content-type", "accept", "content-encoding", "accept-encoding", "connection", "date", "version", "user-agent", "host", "cache-control", "pragma", "expires", "vary", "server") } ?: "other-header"
            log("$safeName: ${safe ?: "██"}")
        }
    }
    fun body(raw: String?, log: (String) -> Unit) {
        if (raw.isNullOrEmpty()) return
        log("")
        SafeJsonBody.render(raw).chunked(3000).forEach(log)
    }
}
