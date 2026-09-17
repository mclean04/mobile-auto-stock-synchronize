package com.example.finance_planning.network

import com.example.finance_planning.R
import com.example.finance_planning.core.AppText
import com.example.finance_planning.core.AppFailure
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.math.BigDecimal

object DnseSigning {
    fun signature(key: String, secret: String, path: String, date: String, nonce: String): String {
        require(path.startsWith("/") && '?' !in path && '#' !in path)
        require(Regex("[a-f0-9]{32}").matches(nonce))
        require(key.none { it == '"' || it == '\r' || it == '\n' })
        val text = "(request-target): get $path\ndate: $date\nnonce: $nonce"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val encoded = URLEncoder.encode(Base64.getEncoder().encodeToString(
            mac.doFinal(text.toByteArray(Charsets.UTF_8))), "UTF-8")
        return "Signature keyId=\"$key\",algorithm=\"hmac-sha256\",headers=\"(request-target) date\",signature=\"$encoded\",nonce=\"$nonce\""
    }
}

class DnseApi(private val key: String,
              private val secret: String, private val production: Boolean) {
    private val host = if (production) "https://openapi.dnse.com.vn" else "https://sb-openapi.dnse.com.vn"
    private fun id(s: String): String {
        if (!Regex("[A-Za-z0-9._-]{1,80}").matches(s)) throw AppFailure(AppText.get(R.string.invalid_dnse_identifier))
        return s
    }
    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): Any {
        val date = ZonedDateTime.now(ZoneOffset.UTC).format(
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US))
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val suffix = if (query.isEmpty()) "" else query.entries.joinToString("&", "?") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val body = try { DnseHttpTransport.request(host + path + suffix, headers = mapOf(
            "X-Api-Key" to key, "X-Signature" to DnseSigning.signature(key, secret, path, date, nonce),
            "Date" to date, "version" to "2026-07-23"))
        } catch (e: HttpFailure) {
            val environment = if (production) AppText.get(R.string.production_live) else AppText.get(R.string.sandbox_test)
            val reason = when (e.code) {
                "invalid_api_key", "OA-401" -> AppText.get(R.string.dnse_api_key_rejected, environment)
                "invalid_signature", "invalid_authorization" -> AppText.get(R.string.dnse_signature_rejected, environment)
                "FORBIDDEN", "OA-403" -> AppText.get(R.string.dnse_access_denied, environment)
                else -> AppText.get(R.string.dnse_fetch_failed, environment)
            }
            throw AppFailure(AppText.get(R.string.http_error_details, reason, e.status, e.code ?: "unclassified"),
                e.status == 429 || e.status >= 500)
        }
        return JSONTokener(body).nextValue()
    }
    suspend fun accounts() = rows(get("/accounts"), "accounts", "data", "items")
    suspend fun balances(account: String) = get("/accounts/${id(account)}/balances")
    suspend fun positions(account: String) = get("/accounts/${id(account)}/positions", mapOf("marketType" to "STOCK"))
    suspend fun detail(account: String, order: String) = get("/accounts/${id(account)}/orders/${id(order)}")
    suspend fun executions(account: String, order: String) = get("/accounts/${id(account)}/executions/${id(order)}")
    suspend fun history(account: String, from: LocalDate, to: LocalDate): List<JSONObject> =
        pages("/accounts/${id(account)}/orders/history", mapOf("marketType" to "STOCK",
            "from" to from.toString(), "to" to to.toString()))
    suspend fun today(account: String, category: String): List<JSONObject> {
        require(category in setOf("NORMAL", "STOP"))
        return pages("/accounts/${id(account)}/orders", mapOf("marketType" to "STOCK", "orderCategory" to category))
    }
    private suspend fun pages(path: String, params: Map<String, String>): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        for (page in 0 until 100) {
            val payload = get(path, params + mapOf("pageIndex" to page.toString(), "pageSize" to "100"))
            val items = rows(payload, "data", "orders", "items")
            if (items.isEmpty()) return result
            val pageIds = items.joinToString("|") { text(it, "id", "orderId") }
            if (!seen.add(pageIds)) throw AppFailure(AppText.get(R.string.dnse_repeated_page))
            result.addAll(items)
            val total = (payload as? JSONObject)?.optInt("total", -1) ?: -1
            if (items.size < 100 || (total >= 0 && result.size >= total)) return result
        }
        throw AppFailure(AppText.get(R.string.dnse_history_page_limit))
    }
    companion object {
        fun text(o: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull {
            if (o.has(it) && !o.isNull(it)) o.get(it).toString() else null
        } ?: throw AppFailure(AppText.get(R.string.dnse_required_field_missing))
        fun rows(payload: Any, vararg keys: String): List<JSONObject> {
            val a = when (payload) {
                is JSONArray -> payload
                is JSONObject -> keys.firstNotNullOfOrNull { payload.optJSONArray(it) }
                    ?: throw AppFailure(AppText.get(R.string.unsupported_dnse_list_format))
                else -> throw AppFailure(AppText.get(R.string.invalid_dnse_response))
            }
            return (0 until a.length()).map { a.getJSONObject(it) }
        }
        fun rowsOrSingle(payload: Any, vararg keys: String): List<JSONObject> = when (payload) {
            is JSONArray -> (0 until payload.length()).map { payload.getJSONObject(it) }
            is JSONObject -> keys.firstNotNullOfOrNull { payload.optJSONArray(it) }?.let { array ->
                (0 until array.length()).map { array.getJSONObject(it) }
            } ?: keys.firstNotNullOfOrNull { payload.optJSONObject(it) }?.let { listOf(it) }
                ?: listOf(payload)
            else -> throw AppFailure(AppText.get(R.string.invalid_dnse_response))
        }
        private fun decimal(raw: JSONObject, vararg names: String): BigDecimal {
            val value = BigDecimal(text(raw, *names))
            require(value.signum() >= 0 && value.scale() <= 8 && value.precision() <= 24)
            return value
        }
        private fun optionalDecimal(raw: JSONObject, vararg names: String): BigDecimal? {
            val field = names.firstOrNull { raw.has(it) && !raw.isNull(it) && raw.optString(it).isNotBlank() }
                ?: return null
            return decimal(raw, field)
        }
        private fun instant(raw: JSONObject, vararg names: String): Instant =
            OffsetDateTime.parse(text(raw, *names)).toInstant()

        fun normalizeExecution(account: String, orderId: String, raw: JSONObject,
                               priceMultiplier: BigDecimal): JSONObject {
            val executed = instant(raw, "executedAt", "tradeDate", "createdDate", "createdAt", "matchTime")
            val updated = runCatching {
                instant(raw, "modifiedDate", "updatedAt", "executedAt", "tradeDate", "createdDate", "createdAt", "matchTime")
            }.getOrElse { executed }
            require(updated >= executed)
            val price = decimal(raw, "price", "fillPrice", "matchPrice") * priceMultiplier
            val fee = optionalDecimal(raw, "fee", "feeAmount", "tradingFee", "commission")
                ?.multiply(priceMultiplier)
            return JSONObject().put("account", account)
                .put("execution_id", text(raw, "id", "executionId", "fillId", "matchId"))
                .put("order_id", raw.optString("orderId", orderId))
                .put("updated_at", updated.toString()).put("executed_at", executed.toString())
                .put("quantity", decimal(raw, "quantity", "fillQuantity", "matchQuantity")
                    .stripTrailingZeros().toPlainString())
                .put("price_vnd", price.stripTrailingZeros().toPlainString())
                .put("fee_vnd", fee?.stripTrailingZeros()?.toPlainString() ?: JSONObject.NULL)
        }

        fun normalizePosition(account: String, raw: JSONObject, priceMultiplier: BigDecimal,
                              observedAt: Instant): JSONObject {
            val symbol = text(raw, "symbol", "instrument", "stockSymbol")
            val average = optionalDecimal(raw, "averagePrice", "avgPrice", "costPrice")
                ?.multiply(priceMultiplier)
            return JSONObject().put("account", account)
                .put("position_id", raw.optString("id", raw.optString("positionId", symbol)))
                .put("updated_at", observedAt.toString()).put("symbol", symbol)
                .put("quantity", decimal(raw, "quantity", "totalQuantity", "openQuantity")
                    .stripTrailingZeros().toPlainString())
                .put("available_quantity", decimal(raw, "availableQuantity", "tradeQuantity", "qmaxSell", "quantity")
                    .stripTrailingZeros().toPlainString())
                .put("average_price_vnd", average?.stripTrailingZeros()?.toPlainString() ?: JSONObject.NULL)
        }

        fun normalizeBalance(account: String, raw: JSONObject, priceMultiplier: BigDecimal,
                             observedAt: Instant): JSONObject {
            // Current OpenAPI separates stock, derivative, bond and egg assets.
            // Stock cash is a VND amount, not a security price in configurable quote units.
            val stock = if (raw.has("stock")) raw.optJSONObject("stock")
                ?: throw AppFailure(AppText.get(R.string.dnse_balances_stock_invalid)) else null
            val source = stock ?: raw
            val cashFields = if (stock != null) arrayOf("availableCash")
                else arrayOf("cash", "cashBalance", "availableCash", "accountBalance")
            if (cashFields.none { source.has(it) && !source.isNull(it) }) {
                if (com.example.finance_planning.BuildConfig.DEBUG)
                    android.util.Log.d("PlanningApp", "SCHEMA_ERROR route=/accounts/{id}/balances field=stock.availableCash reason=missing_cash")
                throw AppFailure(AppText.get(R.string.dnse_cash_field_missing))
            }
            val units = if (stock != null) BigDecimal.ONE else priceMultiplier
            val cash = decimal(source, *cashFields).multiply(units)
            // availableCash is cash, not purchasing power. Missing power remains unknown.
            val buying = optionalDecimal(source, "buyingPower", "purchasingPower", "ppse")
                ?.multiply(units)
            return JSONObject().put("account", account).put("updated_at", observedAt.toString())
                .put("cash_vnd", cash.stripTrailingZeros().toPlainString())
                .put("buying_power_vnd", buying?.stripTrailingZeros()?.toPlainString() ?: JSONObject.NULL)
        }

        fun normalizeOrder(account: String, raw: JSONObject, priceMultiplier: BigDecimal): JSONObject {
            fun amount(vararg names: String) = BigDecimal(text(raw, *names)).also {
                require(it.signum() >= 0 && it.scale() <= 8 && it.precision() <= 24)
            }.stripTrailingZeros().toPlainString()
            fun time(vararg names: String) = OffsetDateTime.parse(text(raw, *names)).toInstant().toString()
            val side = when (text(raw, "side").uppercase(Locale.US)) {
                "NB", "BUY" -> "BUY"; "NS", "SELL" -> "SELL"
                else -> throw AppFailure(AppText.get(R.string.unsupported_dnse_buy_sell_type))
            }
            val quantity = amount("quantity")
            val filled = amount("fillQuantity", "filledQuantity")
            require(BigDecimal(filled) <= BigDecimal(quantity))
            val created = time("createdDate", "createdAt")
            val updated = time("modifiedDate", "updatedAt")
            require(Instant.parse(updated) >= Instant.parse(created))
            val out = JSONObject().put("account", account).put("order_id", text(raw, "id", "orderId"))
                .put("symbol", text(raw, "symbol")).put("side", side)
                .put("order_type", text(raw, "orderType")).put("quantity", quantity)
                .put("filled_quantity", filled).put("status", text(raw, "orderStatus", "status"))
                .put("created_at", created).put("updated_at", updated)
                .put("market_type", raw.optString("marketType", "STOCK"))
                .put("category", raw.optString("orderCategory", "NORMAL"))
            listOf("price_vnd" to arrayOf("price", "orderPrice"),
                "average_fill_price_vnd" to arrayOf("averagePrice", "avgFilledPrice")).forEach { (target, names) ->
                val field = names.firstOrNull { raw.has(it) && !raw.isNull(it) }
                out.put(target, if (field == null) JSONObject.NULL else
                    BigDecimal(amount(field)).multiply(priceMultiplier).stripTrailingZeros().toPlainString())
            }
            return out
        }
    }
}
