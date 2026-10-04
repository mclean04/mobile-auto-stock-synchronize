package com.example.finance_planning.network

import okhttp3.Interceptor
import okhttp3.Request
import java.io.IOException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Per-call DNSE credentials. Never stored on the shared client or used by Backend services. */
class DnseCredentials(private val key: String, private val secret: String,
                      private val production: Boolean, private val tradingToken: String? = null) {
    internal fun attach(request: Request): Request {
        val host = if (production) "openapi.dnse.com.vn" else "sb-openapi.dnse.com.vn"
        if (request.url.scheme != "https" || request.url.host != host || request.url.port != 443 ||
            request.url.username.isNotEmpty() || request.url.password.isNotEmpty())
            throw IOException("DNSE credential environment mismatch")
        val date = ZonedDateTime.now(ZoneOffset.UTC).format(
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US))
        val nonce = UUID.randomUUID().toString().replace("-", "")
        // Sign the actual Retrofit method/path, excluding the query, just before the single dispatch.
        return request.newBuilder()
            .removeHeader("Authorization").removeHeader("X-Firebase-AppCheck").removeHeader("X-Planning-Authorization")
            .removeHeader("trading-token")
            .header("X-Api-Key", key)
            .header("X-Signature", DnseSigning.signature(key, secret, request.url.encodedPath,
                date, nonce, request.method.lowercase(Locale.US)))
            .header("Date", date).header("version", "2026-07-23").header("Accept", "application/json")
            .apply { tradingToken?.let { header("trading-token", it) } }.build()
    }
    override fun toString() = "DnseCredentials(redacted)"
}

/** Follows the DNSE slot's origin/service/context checks; does not refresh tokens or retry requests. */
internal class DnseCredentialsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val credentials = request.tag(DnseCredentials::class.java)
            ?: throw IOException("Missing DNSE request credentials")
        val signed = try { credentials.attach(request) } catch (error: IllegalArgumentException) {
            // OkHttp async dispatch must receive an IOException; never expose credential text.
            throw IOException("Invalid DNSE request credentials", error)
        }
        return chain.proceed(signed)
    }
}
