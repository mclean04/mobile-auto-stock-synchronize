package com.example.finance_planning.core

import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale

/** Cash-only comparison per sub-account. Never combine cash across accounts or use margin buying power. */
object PlanningFunds {
    fun fields(plan: JSONObject) = plan.optJSONObject("fields") ?: plan
    fun side(plan: JSONObject): String = when (fields(plan).optString("Mua/Bán").trim().uppercase(Locale.ROOT)) {
        "MUA", "BUY", "NB" -> "NB"; "BÁN", "BAN", "SELL", "NS" -> "NS"; else -> ""
    }.ifBlank { when (PlanningIntent.parseOrNull(plan)?.side) { "BUY" -> "NB"; "SELL" -> "NS"; else -> "" } }
    fun cancelled(plan: JSONObject): Boolean {
        val f = fields(plan)
        return plan.optString("action").uppercase(Locale.ROOT) == "CANCEL_ORDER" ||
            f.optString("Hành động").uppercase(Locale.ROOT) in setOf("CANCEL_ORDER", "HỦY", "HUỶ", "HỦY LỆNH", "HUỶ LỆNH") ||
            f.optString("Trạng thái").trim().uppercase(Locale.ROOT) in setOf("CANCELLED", "CANCELED", "ĐÃ HỦY", "ĐÃ HUỶ", "HỦY", "HUỶ")
    }
    private fun number(f: JSONObject, key: String): BigDecimal? = f.optString(key).toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
    fun price(plan: JSONObject) = PlanningIntent.parseOrNull(plan)?.let { BigDecimal(it.limitPriceVnd) }
        ?: listOf("Giá LO tối đa", "Giá LO", "Giá LO (VND)").firstNotNullOfOrNull { number(fields(plan), it)?.takeIf { n -> n.signum() > 0 } }
    fun principal(plan: JSONObject): BigDecimal? {
        PlanningIntent.parseOrNull(plan)?.let { return BigDecimal(it.limitPriceVnd).multiply(BigDecimal(it.quantity)) }
        val f = fields(plan)
        val quantity = number(f, "Số lượng")?.takeIf { it.signum() > 0 }
        val computed = quantity?.let { q -> price(plan)?.multiply(q) }
        val stated = number(f, "Giá trị kế hoạch")?.takeIf { it.signum() > 0 }
        return listOfNotNull(computed, stated).maxOrNull()
    }
    fun required(plan: JSONObject, actualPrincipal: BigDecimal? = null): BigDecimal? {
        // Contract v2 currently carries principal but no fee reserve or approved total budget.
        // Treating the absent fee as zero would weaken the existing cash-only rule, so typed
        // BUY intents remain read-only until those canonical budget fields are contracted.
        if (PlanningIntent.parseOrNull(plan) != null) return null
        val f = fields(plan)
        val principal = principal(plan) ?: return null
        val fee = number(f, "Phí dự phòng") ?: BigDecimal.ZERO
        val actual = actualPrincipal ?: principal
        val scaledFee = fee.multiply(actual).divide(principal, 0, java.math.RoundingMode.CEILING).max(fee)
        return listOfNotNull(principal + fee, actual + scaledFee, number(f, "Tổng chi ngân sách")).maxOrNull()
    }
    fun cash(snapshot: JSONObject?, account: String): BigDecimal? = snapshot?.objects("balances")
        ?.firstOrNull { it.optString("account") == account }?.let { OrderContent.number(it, "cash_vnd") }
        ?.takeIf { it.signum() >= 0 }
    fun balances(snapshot: JSONObject?): List<Pair<String, BigDecimal>> = snapshot?.objects("accounts").orEmpty()
        .filter { it.optJSONObject("profile")?.optBoolean("dealAccount", false) == true }
        .mapNotNull { row -> val account = row.optString("account"); cash(snapshot, account)?.let { account to it } }
    fun affordable(snapshot: JSONObject?, plan: JSONObject): Boolean {
        if (cancelled(plan)) return false
        if (side(plan) == "NS") return true
        if (side(plan) != "NB") return false
        val required = required(plan) ?: return false
        return balances(snapshot).any { it.second >= required }
    }
}
