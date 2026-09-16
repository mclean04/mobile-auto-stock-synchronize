package com.example.finance_planning.network

import com.example.finance_planning.BuildConfig
import com.example.finance_planning.core.AppFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.net.URI
import java.util.concurrent.TimeUnit

interface DnseReadService {
    @Streaming
    @GET
    suspend fun get(@Url url: String, @HeaderMap headers: Map<String, String>): Response<ResponseBody>
}

object DnseHttpTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .addInterceptor(ResponseSizeLimitInterceptor())
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(DebugBodyLoggingInterceptor(log = {
                android.util.Log.w("OkHttp", it)
            }))
        }.build()
    private val service = Retrofit.Builder().baseUrl("https://openapi.dnse.com.vn/")
        .client(client).build().create(DnseReadService::class.java)

    suspend fun request(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            uri.host in setOf("openapi.dnse.com.vn", "sb-openapi.dnse.com.vn"))
        try {
            val response = service.get(url, headers + ("Accept" to "application/json"))
            val body = response.body() ?: response.errorBody()
            body?.use {
                val limit = if (response.isSuccessful) 4 * 1024 * 1024 else 4096
                val output = java.io.ByteArrayOutputStream()
                val input = it.byteStream()
                val buffer = ByteArray(8192)
                while (output.size() <= limit) {
                    val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                val bytes = output.toByteArray()
                if (!response.isSuccessful) {
                    val raw = if (bytes.size <= limit) String(bytes, Charsets.UTF_8) else null
                    throw HttpFailure(response.code(), ApiDiagnostics.dnseCode(raw))
                }
                if (bytes.size > limit) throw AppFailure("Phản hồi quá lớn.")
                return@withContext if (response.code() == 204) "{}" else String(bytes, Charsets.UTF_8)
            }
            if (!response.isSuccessful) throw HttpFailure(response.code())
            "{}"
        } catch (e: java.io.IOException) {
            throw AppFailure("Không kết nối được DNSE. Dữ liệu đang chờ được giữ lại. [${ApiDiagnostics.failure(e)}]", true)
        }
    }
}

/** Retrofit buffers error bodies even with @Streaming. Bound that read as well. */
internal class ResponseSizeLimitInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val source = object : okio.ForwardingSource(body.source()) {
            var read = 0L
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                val count = super.read(sink, minOf(byteCount, 4 * 1024 * 1024 + 1L - read))
                if (count > 0) read += count
                if (read > 4 * 1024 * 1024) {
                    close()
                    throw java.io.IOException("Response exceeds size limit")
                }
                return count
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }).build()
    }
}
