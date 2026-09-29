package com.example.finance_planning.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Separate client: OTP, signatures and trading tokens must never enter HTTP debug logs. */
class DnseTradingApi(private val key: String, private val secret: String, production: Boolean,
                     internal val client: OkHttpClient = tradingClient(),
                     private val diagnostic: ((BrokerResponse) -> Unit)? = null,
                     private val qaInMemoryFakeOnly: Boolean = false) {
    init {
        require(com.example.finance_planning.core.DnseCredentialFormat.valid(key, secret)) { "Invalid DNSE credential format" }
        require(!production || diagnostic == null)
        require(!qaInMemoryFakeOnly || (com.example.finance_planning.BuildConfig.DEBUG && !production))
    }
    // An injected fake interceptor may return a response. Any fall-through is denied BEFORE DNS/socket I/O.
    private val guardedClient = if (qaInMemoryFakeOnly) client.newBuilder().addInterceptor {
        throw java.io.IOException("QA_ISOLATION_DENIED", com.example.finance_planning.core.QaIsolationDenied())
    }.build() else client
    private val host = if (production) "https://openapi.dnse.com.vn" else "https://sb-openapi.dnse.com.vn"
    private val service by lazy {
        val builder = guardedClient.newBuilder().singleExchange()
        builder.interceptors().add(0, HttpBodyLimit(65536, omitErrors = diagnostic == null))
        httpService("$host/", builder.build())
    }
    companion object {
        private fun tradingClient() = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
    private fun id(value: String): String = value.also { require(Regex("[A-Za-z0-9._-]{1,80}").matches(it)) }
    private suspend fun call(path: String, method: String = "GET", query: Map<String, String> = emptyMap(),
                             body: JSONObject? = null, token: String? = null): Any = withContext(Dispatchers.IO) {
        if (!qaInMemoryFakeOnly) com.example.finance_planning.core.QaStartupIsolation.requireBusiness()
        val date = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US))
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val suffix = if (query.isEmpty()) "" else query.entries.joinToString("&", "?") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val headers = mutableMapOf("X-Api-Key" to key,
            "X-Signature" to DnseSigning.signature(key, secret, path, date, nonce, method.lowercase(Locale.US)),
            "Date" to date, "version" to "2026-07-23", "Accept" to "application/json")
        token?.let { headers["trading-token"] = it }
        try {
            val response = service.request(host + path + suffix, method, headers,
                if (method == "POST") (body?.toString() ?: "").toRequestBody("application/json".toMediaType()) else null)
            val responseBody = response.body() ?: response.errorBody()
            responseBody.use {
                if (diagnostic == null && !response.isSuccessful) throw TradeHttpFailure(response.code())
                val raw = it?.readLimited(65536) ?: ""
                diagnostic?.invoke(BrokerResponse(method, path + suffix, response.code(),
                    BrokerResponseRedaction.body(raw, listOfNotNull(key, secret, token, body?.optString("passcode")))))
                if (!response.isSuccessful) throw TradeHttpFailure(response.code())
                if (raw.isBlank()) JSONObject() else JSONTokener(raw).nextValue()
            }
        } catch (_: HttpResponseTooLarge) {
            throw IllegalArgumentException("Response exceeds size limit")
        } catch (error: java.io.IOException) {
            // Coroutine stack recovery may wrap IOException while retaining its cause chain.
            if (qaInMemoryFakeOnly && generateSequence<Throwable>(error) { it.cause }.take(8)
                    .any { it is com.example.finance_planning.core.QaIsolationDenied })
                throw com.example.finance_planning.core.QaIsolationDenied()
            throw error
        }
    }

    suspend fun balances(account: String) = call("/accounts/${id(account)}/balances")
    suspend fun accounts() = DnseApi.rows(call("/accounts"), "accounts", "data", "items")
    suspend fun packages(account: String, symbol: String) = DnseApi.rows(call("/accounts/${id(account)}/loan-packages",
        query = mapOf("marketType" to "STOCK", "symbol" to id(symbol))), "loanPackages", "data", "items")
    suspend fun emailOtp() { call("/registration/send-email-otp", "POST") }
    suspend fun token(type: String, otp: String): String {
        require(type in setOf("email_otp", "smart_otp") && Regex("[0-9]{6}").matches(otp))
        val json = call("/registration/trading-token", "POST", body = JSONObject().put("otpType", type).put("passcode", otp)) as JSONObject
        return DnseApi.text(json, "tradingToken", "trading-token").also { require(it.isNotBlank() && it != "null") }
    }
    suspend fun place(account: String, draft: TradeDraft, token: String): JSONObject = call("/accounts/${id(account)}/orders", "POST",
        mapOf("marketType" to "STOCK", "orderCategory" to "NORMAL"), draft.body(), token) as JSONObject
    suspend fun orders(account: String) = call("/accounts/${id(account)}/orders",
        query = mapOf("marketType" to "STOCK", "orderCategory" to "NORMAL"))
    suspend fun order(account: String, order: String) = call("/accounts/${id(account)}/orders/${id(order)}",
        query = mapOf("marketType" to "STOCK", "orderCategory" to "NORMAL"))
    suspend fun ppse(account: String, draft: TradeDraft) = call("/accounts/${id(account)}/ppse",
        query = mapOf("marketType" to "STOCK", "symbol" to draft.symbol,
            "price" to draft.price.toString(), "loanPackageId" to draft.packageId.toString()))
    suspend fun cancel(account: String, order: String, token: String) = call(
        "/accounts/${id(account)}/orders/${id(order)}", "DELETE",
        mapOf("marketType" to "STOCK", "orderCategory" to "NORMAL"), token = token)

}
class TradeHttpFailure(val status: Int) : Exception("HTTP $status")

data class TradeDraft(val symbol: String, val side: String, val quantity: Int, val price: Long, val packageId: Long) {
    fun body(): JSONObject {
        require(Regex("[A-Z][A-Z0-9]{2,9}").matches(symbol))
        require(side in setOf("NB", "NS"))
        require(quantity in 1..999999900 && (quantity < 100 || quantity % 100 == 0))
        require(price in 1..1000000000 && packageId > 0)
        return JSONObject().put("symbol", symbol).put("side", side).put("quantity", quantity)
            .put("price", price).put("loanPackageId", packageId).put("orderType", "LO")
    }
}
