package com.example.finance_planning.network

import com.example.finance_planning.BuildConfig
import com.example.finance_planning.core.NotificationDeliveryPolicy
import okhttp3.*
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.*
import java.io.IOException
import java.net.URI
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal class QaRequestContext(private val verify: () -> Unit) {
    fun check() {
        try { verify() } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("QA_ISOLATION_DENIED", error)
        }
    }
}

/** Fixed legacy QA routes; campaign/body mapping stays in the reviewed QA adapter. */
internal interface QaBackendService {
    @Streaming @GET("qa/status") suspend fun status(@HeaderMap h: Map<String, String>, @Tag c: QaRequestContext): Response<ResponseBody>
    @Streaming @GET("qa/cases/{case}") suspend fun case(@Path("case") case: String, @HeaderMap h: Map<String, String>, @Tag c: QaRequestContext): Response<ResponseBody>
    @Streaming @GET("v1/notifications") suspend fun notifications(@QueryMap(encoded = true) query: Map<String, String>, @HeaderMap h: Map<String, String>, @Tag c: QaRequestContext): Response<ResponseBody>
    @Streaming @GET("v1/notifications/{event}") suspend fun notification(@Path("event") event: String, @HeaderMap h: Map<String, String>, @Tag c: QaRequestContext): Response<ResponseBody>
    @Streaming @POST("v1/notifications/{event}/receipts") suspend fun receipt(@Path("event") event: String, @HeaderMap h: Map<String, String>, @Tag c: QaRequestContext, @Body body: RequestBody): Response<ResponseBody>
    @Streaming @PUT("v1/devices/{device}") suspend fun register(@Path("device") device: String, @HeaderMap h: Map<String, String>, @Tag c: QaRequestContext, @Body body: RequestBody): Response<ResponseBody>
    @Streaming @DELETE("v1/devices/{device}") suspend fun remove(@Path("device") device: String, @HeaderMap h: Map<String, String>, @Tag c: QaRequestContext): Response<ResponseBody>
}

/** One immutable loopback client, with no stored bearer, owner or campaign. */
class QaBackendSlot internal constructor() {
    internal val client: OkHttpClient
    internal val retrofit: Retrofit
    private val service: QaBackendService
    init {
        require(BuildConfig.DEBUG)
        client = OkHttpClient.Builder().singleExchange()
            .proxy(Proxy.NO_PROXY).protocols(listOf(Protocol.HTTP_1_1))
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    require(hostname == "127.0.0.1")
                    return listOf(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                }
            })
            .connectTimeout(0, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (!QaNotificationTransport.allowlisted(request.url.toString(), request.method))
                    throw IOException("QA_ISOLATION_DENIED")
                (request.tag(QaRequestContext::class.java) ?: throw IOException("Missing QA request context")).check()
                HttpBodyLimit(4_000_000).intercept(chain)
            }.build()
        retrofit = Retrofit.Builder().baseUrl(NotificationDeliveryPolicy.QA_BASE_URL + "/").client(client).build()
        service = retrofit.create(QaBackendService::class.java)
    }
    internal suspend fun request(url: String, method: String, headers: Map<String, String>,
                                 body: RequestBody?, verify: () -> Unit): Response<ResponseBody> {
        require(QaNotificationTransport.allowlisted(url, method))
        val uri = URI(url)
        val path = uri.path.split('/').drop(1)
        val context = QaRequestContext(verify).also { it.check() }
        return when {
            path == listOf("qa", "status") -> service.status(headers, context)
            path.size == 3 && path.take(2) == listOf("qa", "cases") -> service.case(path[2], headers, context)
            path == listOf("v1", "notifications") -> {
                val query = uri.rawQuery?.split('&')?.associate {
                    val parts = it.split('=', limit = 2)
                    parts[0] to parts[1]
                } ?: emptyMap()
                service.notifications(query, headers, context)
            }
            path.size == 3 && path[1] == "notifications" -> service.notification(path[2], headers, context)
            path.size == 4 && path[1] == "notifications" -> service.receipt(path[2], headers, context, requireNotNull(body))
            path.size == 3 && path[1] == "devices" && method == "PUT" -> service.register(path[2], headers, context, requireNotNull(body))
            path.size == 3 && path[1] == "devices" && method == "DELETE" -> service.remove(path[2], headers, context)
            else -> error("Unsupported QA endpoint")
        }
    }
}
