package com.example.finance_planning.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
                     internal val client: OkHttpClient = tradingClient()) {
    private val host = if (production) "https://openapi.dnse.com.vn" else "https://sb-openapi.dnse.com.vn"
    companion object {
        private fun tradingClient() = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
    private fun id(value: String): String = value.also { require(Regex("[A-Za-z0-9._-]{1,80}").matches(it)) }
    private suspend fun call(path: String, method: String = "GET", query: Map<String, String> = emptyMap(),
                             body: JSONObject? = null, token: String? = null): Any = withContext(Dispatchers.IO) {
        val date = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US))
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val suffix = if (query.isEmpty()) "" else query.entries.joinToString("&", "?") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val request = Request.Builder().url(host + path + suffix).header("X-Api-Key", key)
            .header("X-Signature", DnseSigning.signature(key, secret, path, date, nonce, method.lowercase(Locale.US)))
            .header("Date", date).header("version", "2026-07-23").header("Accept", "application/json")
        token?.let { request.header("trading-token", it) }
        request.method(method, if (method == "POST") (body?.toString() ?: "").toRequestBody("application/json".toMediaType()) else null)
        client.newCall(request.build()).execute().use { response ->
            // Do not surface broker bodies: they can contain credentials or private account data.
            if (!response.isSuccessful) throw TradeHttpFailure(response.code)
            val source = response.body?.source()
            source?.request(65537)
            val raw = source?.buffer?.readUtf8(minOf(source.buffer.size, 65537)) ?: ""
            require(raw.toByteArray().size <= 65536)
            if (raw.isBlank()) JSONObject() else JSONTokener(raw).nextValue()
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
