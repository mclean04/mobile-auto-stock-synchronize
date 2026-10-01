package com.example.finance_planning.network

import com.example.finance_planning.core.QaStartupIsolation
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Invocation
import retrofit2.Retrofit
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

internal class DnseCallContext(val diagnostics: Boolean, private val check: () -> Unit) {
    fun verify() {
        try { check() } catch (error: Exception) {
            // OkHttp async dispatch must receive an IOException, not an uncaught session exception.
            if (error is java.io.IOException) throw error
            throw SupersededNetworkContext().apply { initCause(error) }
        }
    }
}

/** Application-owned factories. A slot never changes origin, policy or Retrofit instance. */
class NetworkClients {
    private var dnseSlot: DnseSlot? = null
    @Synchronized fun dnse(configuration: DnseConfiguration): DnseSlot {
        dnseSlot?.takeIf { it.configuration == configuration }?.let { return it }
        dnseSlot?.retire()
        return DnseSlot(configuration).also { dnseSlot = it }
    }
    @Synchronized fun invalidateDnse() { dnseSlot?.retire(); dnseSlot = null }

    companion object {
        // Compatibility constructors obtain the same process-owned graph.
        val application = NetworkClients()
        internal fun dnseClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).build()
    }
}

class DnseSlot internal constructor(
    val configuration: DnseConfiguration,
    seed: OkHttpClient = NetworkClients.dnseClient(),
    internal val fakeOnly: Boolean = false,
    logger: (String) -> Unit = { android.util.Log.w("OkHttp", it) }
) {
    @Volatile private var active = true
    private val loggerInterceptor = DebugBodyLoggingInterceptor(logger)
    internal val client: OkHttpClient
    internal val retrofit: Retrofit
    private val read: DnseReadEndpoints
    private val trade: DnseTradeEndpoints
    init {
        require(!fakeOnly || (com.example.finance_planning.BuildConfig.DEBUG && !configuration.production))
        val builder = seed.newBuilder().singleExchange()
        builder.interceptors().add(0, Interceptor { chain ->
            checkActive()
            if (!fakeOnly) QaStartupIsolation.requireBusiness()
            val request = chain.request()
            val context = requireNotNull(request.tag(DnseCallContext::class.java))
            context.verify()
            val origin = configuration.origin.toHttpUrl()
            require(request.url.scheme == origin.scheme && request.url.host == origin.host &&
                request.url.port == origin.port && request.url.username.isEmpty() && request.url.password.isEmpty())
            val type = request.tag(Invocation::class.java)?.method()?.declaringClass
            require(type == DnseReadEndpoints::class.java || type == DnseTradeEndpoints::class.java)
            require(!configuration.production || !context.diagnostics)
            // This outer limit also bounds Retrofit's eager error buffering and fake responses.
            HttpBodyLimit(if (type == DnseReadEndpoints::class.java) 4L * 1024 * 1024 else 65536,
                omitErrors = type == DnseTradeEndpoints::class.java && !context.diagnostics)
                .intercept(chain)
        })
        builder.interceptors().add(1, Interceptor { chain ->
            if (chain.request().tag(Invocation::class.java)?.method()?.declaringClass == DnseReadEndpoints::class.java)
                loggerInterceptor.intercept(chain) else chain.proceed(chain.request())
        })
        if (fakeOnly) builder.addInterceptor {
            throw java.io.IOException("QA_ISOLATION_DENIED", com.example.finance_planning.core.QaIsolationDenied())
        }
        client = builder.build()
        retrofit = Retrofit.Builder().baseUrl(configuration.origin).client(client).build()
        read = retrofit.create(DnseReadEndpoints::class.java)
        trade = retrofit.create(DnseTradeEndpoints::class.java)
    }
    internal fun checkActive() { if (!active) throw SupersededNetworkContext() }
    internal fun retire() { active = false; client.dispatcher.cancelAll() }
    private fun parts(path: String): List<String> = path.removePrefix("/").split('/').also { parts ->
        require(path.startsWith('/') && parts.all { Regex("[A-Za-z0-9._-]{1,80}").matches(it) && it !in setOf(".", "..") })
    }
    private fun query(values: Map<String, String>) = values.mapValues { URLEncoder.encode(it.value, "UTF-8") }
    internal suspend fun read(path: String, query: Map<String, String>, headers: Map<String, String>,
                              checkContext: () -> Unit = {}): retrofit2.Response<ResponseBody> {
        checkActive(); checkContext(); val p = parts(path); val q = query(query)
        val context = DnseCallContext(false, checkContext)
        return when {
            p == listOf("accounts") -> read.accounts(headers, context)
            p.size == 3 && p[0] == "accounts" -> when (p[2]) {
                "balances" -> read.balances(p[1], headers, context)
                "positions" -> read.positions(p[1], q, headers, context)
                "orders" -> read.orders(p[1], q, headers, context)
                else -> error("Unsupported DNSE read endpoint")
            }
            p.size == 4 && p[0] == "accounts" && p[2] == "orders" ->
                if (p[3] == "history") read.history(p[1], q, headers, context) else read.order(p[1], p[3], headers, context)
            p.size == 4 && p[0] == "accounts" && p[2] == "executions" -> read.executions(p[1], p[3], headers, context)
            else -> error("Unsupported DNSE read endpoint")
        }
    }
    internal suspend fun trade(path: String, method: String, query: Map<String, String>, headers: Map<String, String>,
                               body: RequestBody?, diagnostics: Boolean = false,
                               checkContext: () -> Unit = {}): retrofit2.Response<ResponseBody> {
        checkActive(); checkContext(); val p = parts(path); val q = query(query)
        val context = DnseCallContext(diagnostics, checkContext)
        return when {
            method == "GET" && p == listOf("accounts") -> trade.accounts(headers, context)
            method == "POST" && p == listOf("registration", "send-email-otp") -> trade.emailOtp(headers, context, body ?: byteArrayOf().toRequestBody())
            method == "POST" && p == listOf("registration", "trading-token") -> trade.token(headers, context, requireNotNull(body))
            p.size == 3 && p[0] == "accounts" && method == "GET" -> when (p[2]) {
                "balances" -> trade.balances(p[1], headers, context)
                "loan-packages" -> trade.packages(p[1], q, headers, context)
                "ppse" -> trade.ppse(p[1], q, headers, context)
                "orders" -> trade.orders(p[1], q, headers, context)
                else -> error("Unsupported DNSE trading endpoint")
            }
            p.size == 3 && p[0] == "accounts" && p[2] == "orders" && method == "POST" -> trade.place(p[1], q, headers, context, requireNotNull(body))
            p.size == 4 && p[0] == "accounts" && p[2] == "orders" && method == "GET" -> trade.order(p[1], p[3], q, headers, context)
            p.size == 4 && p[0] == "accounts" && p[2] == "orders" && method == "DELETE" -> trade.cancel(p[1], p[3], q, headers, context)
            else -> error("Unsupported DNSE trading endpoint")
        }
    }
}
