package com.example.finance_planning.core

import org.json.JSONObject

data class PlanningActionDecision(
    val intent: PlanningIntent?,
    val reasons: List<EligibilityReason>,
    val canExecute: Boolean,
    val cachedReadOnly: Boolean
)

/** Pure UI gate. Server preflight remains the final authority immediately before DNSE. */
object PlanningActionPolicy {
    fun evaluate(row: JSONObject, activeProduction: Boolean?, freshForAction: Boolean): PlanningActionDecision {
        val intent = PlanningIntent.parseOrNull(row)
        val environmentMatches = intent != null && activeProduction != null &&
            activeProduction == (intent.environment == TradingEnvironment.PRODUCTION)
        val reasons = when {
            intent == null && row.optString("contract_version") == "2.0" ->
                listOf(EligibilityReason.INVALID_INTENT)
            intent == null -> listOf(EligibilityReason.LEGACY_READ_ONLY)
            else -> intent.eligibility.reasons + if (environmentMatches) emptyList()
                else listOf(EligibilityReason.WRONG_ENVIRONMENT)
        }.distinct()
        val eligibleHere = intent?.executable == true && environmentMatches && reasons.isEmpty()
        return PlanningActionDecision(intent, reasons, eligibleHere && freshForAction,
            eligibleHere && !freshForAction)
    }
}
