package com.example.finance_planning.network

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response

/**
 * Validated local Backend stale HTTP/1.1 pool prevention. OkHttp marks a Connection: close exchange
 * non-reusable, even if the peer ignores the header. One dispatch, never a retry.
 * The shared client remains shared; HTTP/2 multiplexing and DNSE policy are unchanged.
 */
internal class BackendHttp1ConnectionPolicy : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val outgoing = if (chain.connection()?.protocol() == Protocol.HTTP_1_1)
            request.newBuilder().header("Connection", "close").build()
        else request
        return chain.proceed(outgoing)
    }
}
