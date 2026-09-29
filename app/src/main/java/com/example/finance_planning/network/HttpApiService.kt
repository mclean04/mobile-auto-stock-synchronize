package com.example.finance_planning.network

import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.*
import java.io.IOException

/** Raw JSON stays byte-for-byte caller-owned; Retrofit owns dispatch and coroutine cancellation. */
internal interface HttpApiService {
    @Streaming @GET suspend fun get(@Url url: String, @HeaderMap headers: Map<String, String>): Response<ResponseBody>
    @Streaming @POST suspend fun post(@Url url: String, @HeaderMap headers: Map<String, String>, @Body body: RequestBody): Response<ResponseBody>
    @Streaming @PUT suspend fun put(@Url url: String, @HeaderMap headers: Map<String, String>, @Body body: RequestBody): Response<ResponseBody>
    @Streaming @PATCH suspend fun patch(@Url url: String, @HeaderMap headers: Map<String, String>, @Body body: RequestBody): Response<ResponseBody>
    @Streaming @DELETE suspend fun delete(@Url url: String, @HeaderMap headers: Map<String, String>): Response<ResponseBody>
}

internal fun httpService(baseUrl: String, client: OkHttpClient): HttpApiService =
    Retrofit.Builder().baseUrl(baseUrl).client(client).build().create(HttpApiService::class.java)

internal suspend fun HttpApiService.request(url: String, method: String, headers: Map<String, String>,
                                          body: RequestBody? = null): Response<ResponseBody> = when (method) {
    "GET" -> { require(body == null); get(url, headers) }
    "DELETE" -> { require(body == null); delete(url, headers) }
    "POST" -> post(url, headers, body ?: byteArrayOf().toRequestBody())
    "PUT" -> put(url, headers, body ?: byteArrayOf().toRequestBody())
    "PATCH" -> patch(url, headers, body ?: byteArrayOf().toRequestBody())
    else -> error("Unsupported HTTP method")
}

private class ExchangeState {
    var dispatched = false
    var originalHeaders: okhttp3.Headers? = null
}

/** No redirects/auth followups/replays, including OkHttp's Retry-After: 0 handling. */
internal fun OkHttpClient.Builder.singleExchange(): OkHttpClient.Builder = apply {
    followRedirects(false); followSslRedirects(false); retryOnConnectionFailure(false)
    authenticator(Authenticator.NONE); proxyAuthenticator(Authenticator.NONE)
    interceptors().add(0, Interceptor { chain ->
        val state = ExchangeState()
        val response = chain.proceed(chain.request().newBuilder().tag(ExchangeState::class.java, state).build())
        state.originalHeaders?.let { response.newBuilder().headers(it).build() } ?: response
    })
    addNetworkInterceptor { chain ->
        val state = requireNotNull(chain.request().tag(ExchangeState::class.java))
        if (state.dispatched) throw IOException("Automatic HTTP replay denied")
        state.dispatched = true
        val response = chain.proceed(chain.request())
        if (response.code == 503 && response.header("Retry-After") == "0") {
            // Prevent the internal followup; restore the original header before exposing the response.
            state.originalHeaders = response.headers
            response.newBuilder().header("Retry-After", Int.MAX_VALUE.toString()).build()
        } else response
    }
}

internal class HttpResponseTooLarge : IOException("Response exceeds size limit")

/** Also bounds Retrofit's eager error-body buffering; success bodies remain @Streaming. */
internal class HttpBodyLimit(private val maxBytes: Long, private val omitErrors: Boolean = false,
                             private val truncateErrors: Boolean = false) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        if (!response.isSuccessful && omitErrors) {
            body.close()
            return response.newBuilder().body(byteArrayOf().toResponseBody(body.contentType())).build()
        }
        val truncate = !response.isSuccessful && truncateErrors
        val source = object : okio.ForwardingSource(body.source()) {
            var count = 0L
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                if (truncate && count >= maxBytes) return -1
                val read = super.read(sink, minOf(byteCount, maxBytes + 1 - count))
                if (read > 0) count += read
                if (count > maxBytes) { close(); throw HttpResponseTooLarge() }
                return read
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = if (truncate) -1L else body.contentLength()
            override fun source() = source
        }).build()
    }
}

internal fun ResponseBody.readLimited(maxBytes: Long): String {
    val input = source()
    input.request(maxBytes + 1)
    if (input.buffer.size > maxBytes) throw HttpResponseTooLarge()
    return input.buffer.readUtf8()
}
