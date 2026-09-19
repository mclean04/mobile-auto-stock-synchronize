package com.example.finance_planning.network

import okhttp3.Interceptor
import okhttp3.Response

/** Raw OkHttp-style diagnostics, debug-only even if installed accidentally in release. */
class DebugBodyLoggingInterceptor(private val log: (String) -> Unit, private val enabled: Boolean = com.example.finance_planning.BuildConfig.DEBUG) : Interceptor {
    companion object { const val MAX_BODY = 4L * 1024 * 1024 }
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!com.example.finance_planning.BuildConfig.DEBUG || !enabled) return chain.proceed(chain.request())
        val request = chain.request()
        val method = request.method.takeIf { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD") } ?: "other"
        val url = request.url.toString()
        val started = System.nanoTime()
        log("--> $method $url")
        HttpLogFormat.headers(request.headers.map { it.first to it.second }, log)
        val requestBody = request.body
        if (requestBody != null && !requestBody.isDuplex() && !requestBody.isOneShot()) {
            val buffer = okio.Buffer()
            requestBody.writeTo(buffer)
            HttpLogFormat.body(buffer.readUtf8(), log)
        }
        log("--> END $method")
        try {
            val response = chain.proceed(request)
            try {
                val preview = response.peekBody(MAX_BODY + 1).use { it.bytes() }
                val raw = if (preview.size <= MAX_BODY) String(preview, Charsets.UTF_8) else null
                val code = if (!response.isSuccessful) ApiDiagnostics.dnseCode(raw) else null
                log("<-- ${response.code} $url (${(System.nanoTime() - started) / 1_000_000}ms)")
                HttpLogFormat.headers(response.headers.map { it.first to it.second }, log)
                if (code != null) log("DNSE-error-code: $code")
                if (raw != null) HttpLogFormat.body(raw, log) else log("(body omitted: exceeds app response limit)")
                log("<-- END HTTP (${if (raw == null) ">4194304" else preview.size.toString()}-byte body)")
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
