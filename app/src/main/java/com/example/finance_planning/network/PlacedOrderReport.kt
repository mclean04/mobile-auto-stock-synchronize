package com.example.finance_planning.network

import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

/** Builds the strict payload accepted by POST /v1/orders/placed. */
object PlacedOrderReport {
    fun payload(requestId: String, deviceId: String, environment: String, account: String,
                draft: TradeDraft, response: JSONObject, observedAt: Instant): JSONObject {
        require(environment in setOf("production", "sandbox"))
        val raw = response.optJSONObject("data") ?: response.optJSONObject("order") ?: response
        val order = runCatching { DnseApi.normalizeOrder(account, raw, BigDecimal.ONE) }
            .getOrElse {
                val id = DnseApi.text(raw, "id", "orderId")
                require(id.isNotBlank() && id != "null")
                val filled = raw.optString("fillQuantity",
                    raw.optString("filledQuantity", "0")).ifBlank { "0" }
                JSONObject().put("account", account).put("order_id", id)
                    .put("symbol", draft.symbol).put("side", if (draft.side == "NB") "BUY" else "SELL")
                    .put("order_type", "LO").put("quantity", draft.quantity.toString())
                    .put("filled_quantity", filled).put("price_vnd", draft.price.toString())
                    .put("average_fill_price_vnd", JSONObject.NULL)
                    .put("status", raw.optString("orderStatus",
                        raw.optString("status", "PENDING")).ifBlank { "PENDING" })
                    .put("market_type", "STOCK").put("category", "NORMAL")
                    .put("created_at", observedAt.toString()).put("updated_at", observedAt.toString())
            }
        return JSONObject().put("request_id", requestId).put("device_id", deviceId)
            .put("environment", environment).put("order", order)
    }
}
