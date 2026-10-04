package com.example.finance_planning.network

import okhttp3.Interceptor
import okhttp3.Request

/** Immutable, per-call SDK result. Never retained by the shared client or refreshed on an OkHttp thread. */
class BackendCredentials(headers: Map<String, String>, private val checkCurrent: () -> Unit = {}) {
    private val values = headers.filterKeys { isCredential(it) }.toMap()
    init { require(headers.keys.none { it.equals("X-Planning-Authorization", ignoreCase = true) }) }
    fun check() {
        try { checkCurrent() } catch (error: Exception) {
            if (error is java.io.IOException) throw error
            throw SupersededNetworkContext().apply { initCause(error) }
        }
    }
    internal fun attach(request: Request): Request {
        check()
        return request.newBuilder().removeHeader("Authorization").removeHeader("X-Firebase-AppCheck")
            .removeHeader("X-Planning-Authorization").apply {
                values.forEach { (name, value) -> header(name, value) }
            }.build()
    }
    override fun toString() = "BackendCredentials(redacted)"
    companion object {
        fun isCredential(name: String) = name.equals("Authorization", true) || name.equals("X-Firebase-AppCheck", true)
        suspend fun capture(headers: suspend () -> Map<String, String>, check: () -> Unit): BackendCredentials {
            check()
            return BackendCredentials(headers(), check).also { it.check() }
        }
    }
}

/** Installed only in the origin-checked ordinary Backend slot, before local debug logging. */
internal class BackendCredentialsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val credentials = request.tag(BackendCredentials::class.java) ?: BackendCredentials(
            request.headers.toMap()) {
            request.tag(SessionRequestContext::class.java)?.check()
            request.tag(com.example.finance_planning.network.mobile.MobileRequestContext::class.java)?.check()
        }
        return chain.proceed(credentials.attach(request))
    }
}
