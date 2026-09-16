package com.example.finance_planning.network

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

class DnseApi(private val transport: Transport, private val key: String,
              private val secret: String, private val production: Boolean) {
    private val host = if (production) "https://openapi.dnse.com.vn" else "https://sb-openapi.dnse.com.vn"
    private fun id(s: String): String {
        if (!Regex("[A-Za-z0-9._-]{1,80}").matches(s)) throw AppFailure("Mã DNSE không hợp lệ.")
        return s
    }
    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): Any {
        val date = ZonedDateTime.now(ZoneOffset.UTC).format(
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US))
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val suffix = if (query.isEmpty()) "" else query.entries.joinToString("&", "?") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val body = try { transport.request(host + path + suffix, headers = mapOf(
            "X-Api-Key" to key, "X-Signature" to DnseSigning.signature(key, secret, path, date, nonce),
            "Date" to date, "version" to "2026-07-23"))
        } catch (e: HttpFailure) {
            if (e.status == 401 || e.status == 403)
                throw AppFailure("DNSE từ chối khóa hoặc chữ ký. Kiểm tra môi trường, khóa và giờ trên điện thoại.")
            throw AppFailure("DNSE trả lỗi HTTP ${e.status}; chưa hoàn tất lấy dữ liệu.",
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
            if (!seen.add(pageIds)) throw AppFailure("DNSE lặp lại trang dữ liệu; đã dừng để tránh thiếu lệnh.")
            result.addAll(items)
            val total = (payload as? JSONObject)?.optInt("total", -1) ?: -1
            if (items.size < 100 || (total >= 0 && result.size >= total)) return result
        }
        throw AppFailure("Lịch sử vượt 100 trang; cần chia nhỏ khoảng ngày.")
    }
    companion object {
        fun text(o: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull {
            if (o.has(it) && !o.isNull(it)) o.get(it).toString() else null
        } ?: throw AppFailure("DNSE thiếu trường dữ liệu bắt buộc; chưa gửi bản ghi lên backend.")
        fun rows(payload: Any, vararg keys: String): List<JSONObject> {
            val a = when (payload) {
                is JSONArray -> payload
                is JSONObject -> keys.firstNotNullOfOrNull { payload.optJSONArray(it) }
                    ?: throw AppFailure("Định dạng danh sách DNSE chưa được hỗ trợ.")
                else -> throw AppFailure("Phản hồi DNSE không hợp lệ.")
            }
            return (0 until a.length()).map { a.getJSONObject(it) }
        }
        fun normalizeOrder(account: String, raw: JSONObject, priceMultiplier: BigDecimal): JSONObject {
            fun amount(vararg names: String) = BigDecimal(text(raw, *names)).also {
                require(it.signum() >= 0 && it.scale() <= 8 && it.precision() <= 24)
            }.stripTrailingZeros().toPlainString()
            fun time(vararg names: String) = OffsetDateTime.parse(text(raw, *names)).toInstant().toString()
            val side = when (text(raw, "side").uppercase(Locale.US)) {
                "NB", "BUY" -> "BUY"; "NS", "SELL" -> "SELL"
                else -> throw AppFailure("Loại mua/bán DNSE chưa được hỗ trợ.")
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
