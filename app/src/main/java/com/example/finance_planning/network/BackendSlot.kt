package com.example.finance_planning.network

import com.example.finance_planning.core.Contracts
import com.example.finance_planning.core.QaStartupIsolation
import com.example.finance_planning.network.mobile.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Invocation
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class BackendWireVersion { LEGACY, MOBILE_V1 }

/** Ordinary Backend only. QA retains its reviewed isolated transport until its own migration. */
data class BackendConfiguration(val wireVersion: BackendWireVersion = BackendWireVersion.LEGACY,
    val sessionEnabled: Boolean = com.example.finance_planning.BuildConfig.LOCAL_BACKEND) {
    val origin: String get() = Contracts.BACKEND + "/"
}

/** Inactive container boundary: PlanningApp does not yet obtain Backend clients here. */
class BackendSlot internal constructor(
    val configuration: BackendConfiguration,
    seed: OkHttpClient = defaultClient(),
    logger: (String) -> Unit = { android.util.Log.w("OkHttp", it) }
) {
    @Volatile private var active = true
    internal val client: OkHttpClient
    internal val retrofit: Retrofit
    internal val legacy: HttpApiService
    private val mobile: MobileBackendService
    internal val session: BackendEndpoint
    init {
        QaStartupIsolation.requireBusiness()
        val origin = configuration.origin.toHttpUrl()
        val builder = seed.newBuilder().singleExchange()
        if (com.example.finance_planning.core.LocalBackend.active) {
            check(com.example.finance_planning.core.LocalBackend.accepts(configuration.origin))
            builder.proxy(java.net.Proxy.NO_PROXY)
        }
        builder.interceptors().add(0, okhttp3.Interceptor { chain ->
            checkActive()
            if (QaStartupIsolation.active) throw IOException("QA_ISOLATION_DENIED")
            val request = chain.request()
            if (request.url.scheme != origin.scheme || request.url.host != origin.host ||
                request.url.port != origin.port || request.url.username.isNotEmpty() ||
                request.url.password.isNotEmpty()) throw IOException("Backend origin mismatch")
            val type = request.tag(Invocation::class.java)?.method()?.declaringClass
            when (type) {
                BackendEndpoint::class.java -> {
                    if (request.url.encodedPath == "/mobile/v2/session") {
                        if (!configuration.sessionEnabled) throw IOException("Session Backend contract is inactive")
                        (request.tag(SessionRequestContext::class.java)
                            ?: throw IOException("Missing session request context")).check()
                    } else {
                        if (configuration.wireVersion != BackendWireVersion.LEGACY)
                            throw IOException("Planning Backend contract is inactive")
                        (request.tag(BackendCredentials::class.java)
                            ?: throw IOException("Missing planning request context")).check()
                    }
                }
                MobileBackendService::class.java -> {
                    if (configuration.wireVersion != BackendWireVersion.MOBILE_V1)
                        throw IOException("Mobile Backend contract is inactive")
                    (request.tag(MobileRequestContext::class.java)
                        ?: throw IOException("Missing Backend request context")).check()
                }
                HttpApiService::class.java -> {
                    if (configuration.wireVersion != BackendWireVersion.LEGACY || request.url.encodedPath.startsWith("/mobile/"))
                        throw IOException("Legacy Backend contract is inactive")
                }
                else -> throw IOException("Unsupported Backend service")
            }
            HttpBodyLimit(4_194_304, truncateErrors = type != BackendEndpoint::class.java && configuration.wireVersion == BackendWireVersion.LEGACY)
                .intercept(chain)
        })
        builder.interceptors().add(1, BackendCredentialsInterceptor())
        builder.interceptors().add(2, DebugBodyLoggingInterceptor(logger, fullBodies = true))
        if (com.example.finance_planning.BuildConfig.DEBUG)
            builder.eventListenerFactory { BackendExchangeDiagnostics(logger) }
        client = builder.build()
        retrofit = Retrofit.Builder().baseUrl(configuration.origin).client(client)
            .addConverterFactory(com.example.finance_planning.network.session.SessionConverter())
            .addConverterFactory(MobileContractConverter())
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create(com.example.finance_planning.network.session.SessionGson.gson)).build()
        session = retrofit.create(BackendEndpoint::class.java)
        legacy = retrofit.create(HttpApiService::class.java)
        mobile = retrofit.create(MobileBackendService::class.java)
    }
    fun sessionDataSource(headers: suspend () -> Map<String, String>): com.example.finance_planning.network.session.SessionRemoteDataSource {
        check(configuration.sessionEnabled)
        checkActive()
        return com.example.finance_planning.network.session.SessionRemoteDataSource(session, headers)
    }
    fun mobileDataSource(identity: MobileIdentityProvider): MobileBackendDataSource {
        QaStartupIsolation.requireBusiness()
        check(configuration.wireVersion == BackendWireVersion.MOBILE_V1) { "Mobile Backend contract is inactive" }
        checkActive()
        return MobileBackendDataSource(mobile, MobileRequestExecutor(identity, ::checkActive))
    }
    internal fun checkActive() { if (!active) throw SupersededNetworkContext() }
    internal fun retire() { active = false; client.dispatcher.cancelAll() }
    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }
}
