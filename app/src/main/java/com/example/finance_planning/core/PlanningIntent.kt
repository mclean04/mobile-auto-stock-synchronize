package com.example.finance_planning.core

import com.example.finance_planning.R
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.Duration
import java.util.UUID

enum class AuthoringState { DRAFT, TENTATIVE, READY_FOR_REVIEW, APPROVED, REJECTED, WITHDRAWN, SUPERSEDED }
enum class ExecutionState { NOT_STARTED, PLACEMENT_UNKNOWN, PLACED, PARTIALLY_FILLED, FILLED,
    CANCEL_REQUESTED, CANCEL_UNKNOWN, CANCELLED, BROKER_REJECTED, EXPIRED }
enum class TradingEnvironment { SANDBOX, PRODUCTION }

enum class EligibilityReason {
    EXECUTION_PATH_DISABLED, LEGACY_READ_ONLY, STALE_VERSION, NOT_APPROVED, RESEARCH_ONLY_MONTH, SUPERSEDED,
    WITHDRAWN, TERMINAL_STATE, UNKNOWN_EXECUTION_STATE, ALREADY_PLACED,
    OUTSIDE_EXECUTION_WINDOW, MISSING_EXECUTION_WINDOW, WRONG_ENVIRONMENT, WRONG_ACCOUNT,
    WRONG_QUANTITY, WRONG_LIMIT_PRICE, RECIPIENT_MISMATCH, MISSING_ACCOUNT, MISSING_RECIPIENT,
    INVALID_INTENT;

    companion object {
        fun parse(value: String): EligibilityReason = entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unknown eligibility reason")
    }
}

data class IntentEligibility(val eligible: Boolean, val reasons: List<EligibilityReason>) {
    init { require(!eligible || reasons.isEmpty()) }
}

data class PlanningSourceContext(val sourceId: String, val sourceGeneration: Long) {
    init {
        require(Regex("[A-Za-z0-9_-]{10,200}").matches(sourceId))
        require(sourceGeneration > 0)
    }
    fun json(): JSONObject = JSONObject().put("source_id", sourceId)
        .put("source_generation", sourceGeneration)
    companion object {
        fun parse(json: JSONObject): PlanningSourceContext {
            val generation = json.get("source_generation")
            require(generation is Int || generation is Long)
            return PlanningSourceContext(json.getString("source_id"), (generation as Number).toLong())
        }
    }
}

data class CashRequirements(
    val principalVnd: BigDecimal,
    val feeReserveVnd: BigDecimal,
    val requiredCashVnd: BigDecimal,
    val feeReserveRate: BigDecimal
) {
    fun sameAs(other: CashRequirements): Boolean =
        principalVnd.compareTo(other.principalVnd) == 0 &&
            feeReserveVnd.compareTo(other.feeReserveVnd) == 0 &&
            requiredCashVnd.compareTo(other.requiredCashVnd) == 0 &&
            feeReserveRate.compareTo(other.feeReserveRate) == 0

    companion object {
        private fun decimalString(json: JSONObject, key: String): BigDecimal {
            require(json.get(key) is String)
            return BigDecimal(json.getString(key)).also { require(it.signum() >= 0 && it.scale() <= 8) }
        }
        fun parse(json: JSONObject, side: String, quantity: Int, price: Long): CashRequirements {
            require(json.getString("currency") == "VND")
            require(json.getString("policy_version") == "cash-v1")
            require(json.getBoolean("cash_only"))
            val principal = decimalString(json, "principal_vnd")
            val fee = decimalString(json, "fee_reserve_vnd")
            val required = decimalString(json, "required_cash_vnd")
            val rate = decimalString(json, "fee_reserve_rate").also {
                require(it.signum() > 0 && it <= BigDecimal("0.1"))
            }
            val canonicalPrincipal = BigDecimal(price).multiply(BigDecimal(quantity))
            require(principal.compareTo(canonicalPrincipal) == 0)
            val canonicalFee = principal.multiply(rate).setScale(0, java.math.RoundingMode.CEILING)
            require(fee.compareTo(canonicalFee) == 0)
            if (side == "BUY") {
                require(required.compareTo(principal.add(fee)) == 0)
            } else {
                require(required.signum() == 0)
            }
            return CashRequirements(principal, fee, required, rate)
        }
    }
}

data class PlanningIntent(
    val planId: UUID,
    val intentId: UUID,
    val version: Int,
    val authoringState: AuthoringState,
    val executionState: ExecutionState,
    val environment: TradingEnvironment,
    val recipientUid: String?,
    val account: String?,
    val symbol: String,
    val side: String,
    val quantity: Int,
    val limitPriceVnd: Long,
    val scheduledAt: Instant,
    val windowStartsAt: Instant,
    val windowEndsAt: Instant,
    val sourceContext: PlanningSourceContext,
    val cashRequirements: CashRequirements,
    val eligibility: IntentEligibility,
    val raw: JSONObject
) {
    val executable: Boolean get() = eligibility.eligible &&
        authoringState == AuthoringState.APPROVED && executionState == ExecutionState.NOT_STARTED &&
        account != null && recipientUid != null

    fun matchesDraft(symbol: String, side: String, quantity: Int, price: Long): Boolean =
        this.symbol == symbol && this.side == side && this.quantity == quantity && this.limitPriceVnd == price

    companion object {
        private fun instant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
        private fun decimalText(json: JSONObject, key: String): BigDecimal {
            require(json.get(key) is String)
            return BigDecimal(json.getString(key)).also { require(it.signum() > 0 && it.scale() <= 8) }
        }

        fun parse(json: JSONObject): PlanningIntent {
            require(json.getString("contract_version") == "2.0")
            val version = json.getInt("version").also { require(it > 0) }
            val environment = when (json.getString("environment")) {
                "sandbox" -> TradingEnvironment.SANDBOX
                "production" -> TradingEnvironment.PRODUCTION
                else -> throw IllegalArgumentException("Unknown trading environment")
            }
            val symbol = json.getString("symbol").also {
                require(Regex("[A-Z][A-Z0-9]{2,9}").matches(it))
            }
            val side = json.getString("side").also { require(it in setOf("BUY", "SELL")) }
            val quantity = decimalText(json, "quantity").intValueExact().also {
                require(it in 1..999999900)
            }
            val price = decimalText(json, "limit_price_vnd").longValueExact().also {
                require(it in 1..1_000_000_000L)
            }
            val sourceContext = PlanningSourceContext.parse(json.getJSONObject("source_context"))
            val cashRequirements = CashRequirements.parse(json.getJSONObject("cash_requirements"),
                side, quantity, price)
            val starts = instant(json.getString("window_starts_at"))
            val ends = instant(json.getString("window_ends_at"))
            require(ends > starts)
            val gate = json.getJSONObject("eligibility")
            val reasonsArray = gate.getJSONArray("reasons")
            val reasons = (0 until reasonsArray.length()).map { EligibilityReason.parse(reasonsArray.getString(it)) }
            val eligibility = IntentEligibility(gate.getBoolean("eligible"), reasons)
            fun nullableText(key: String) = if (!json.has(key) || json.isNull(key)) null
                else json.getString(key).takeIf { it.isNotBlank() }
            return PlanningIntent(
                UUID.fromString(json.getString("plan_id")), UUID.fromString(json.getString("intent_id")),
                version, AuthoringState.valueOf(json.getString("authoring_state")),
                ExecutionState.valueOf(json.getString("execution_state")), environment,
                nullableText("recipient_uid"), nullableText("account"), symbol, side, quantity, price,
                instant(json.getString("scheduled_at")), starts, ends, sourceContext, cashRequirements, eligibility,
                JSONObject(json.toString())
            )
        }

        fun parseOrNull(json: JSONObject): PlanningIntent? = runCatching { parse(json) }.getOrNull()
    }
}

data class PreflightAuthorization(
    val preflightId: UUID?,
    val intentId: UUID,
    val currentVersion: Int,
    val serverTime: Instant,
    val expiresAt: Instant?,
    val sourceContext: PlanningSourceContext,
    val cashRequirements: CashRequirements,
    val eligibility: IntentEligibility
) {
    companion object {
        fun parse(json: JSONObject, expected: PlanningIntent): PreflightAuthorization {
            if (json.has("contract_version")) require(json.getString("contract_version") == "2.0")
            val gate = json.optJSONObject("eligibility") ?: json
            val values = gate.getJSONArray("reasons")
            val reasons = (0 until values.length()).map { EligibilityReason.parse(values.getString(it)) }
            val eligibility = IntentEligibility(gate.getBoolean("eligible"), reasons)
            val version = when {
                json.has("current_version") -> json.getInt("current_version")
                json.has("version") -> json.getInt("version")
                else -> expected.version
            }.also { require(it > 0) }
            val intentId = if (json.has("intent_id")) UUID.fromString(json.getString("intent_id"))
                else expected.intentId
            val preflightId = json.optString("preflight_id").takeIf { it.isNotBlank() && it != "null" }
                ?.let(UUID::fromString)
            if (eligibility.eligible) require(preflightId != null)
            val serverTime = OffsetDateTime.parse(json.getString("server_time")).toInstant()
            val expiresAt = json.optString("preflight_expires_at")
                .takeIf { it.isNotBlank() && it != "null" }?.let { OffsetDateTime.parse(it).toInstant() }
            if (eligibility.eligible) require(expiresAt != null && expiresAt > serverTime &&
                Duration.between(serverTime, expiresAt) <= Duration.ofMinutes(5))
            val sourceContext = PlanningSourceContext.parse(json.getJSONObject("source_context"))
            val cashRequirements = CashRequirements.parse(json.getJSONObject("cash_requirements"),
                expected.side, expected.quantity, expected.limitPriceVnd)
            return PreflightAuthorization(
                preflightId, intentId, version, serverTime, expiresAt, sourceContext,
                cashRequirements, eligibility
            )
        }
    }
}

object PlanningContract {
    fun activeSource(payload: JSONObject): PlanningSourceContext {
        require(payload.getString("contract_version") == "2.0")
        require(payload.getString("state") == "ACTIVE")
        return PlanningSourceContext.parse(payload.getJSONObject("source_context"))
    }

    fun preflightRequest(intent: PlanningIntent, account: String, observedAt: Instant): JSONObject =
        JSONObject().put("expected_version", intent.version)
            .put("environment", intent.environment.name.lowercase()).put("account", account)
            .put("quantity", intent.quantity.toString()).put("limit_price_vnd", intent.limitPriceVnd.toString())
            .put("source_context", intent.sourceContext.json())
            .put("observed_at", observedAt.toString())

    fun legacyReadOnly(payload: JSONObject): JSONObject = JSONObject(payload.toString())
        .put("_legacy_read_only", true)

    fun page(items: List<JSONObject>, nextCursor: String? = null): JSONObject = JSONObject()
        .put("contract_version", "2.0").put("items", JSONArray(items))
        .put("next_cursor", nextCursor ?: JSONObject.NULL)

    fun pageSource(page: JSONObject): PlanningSourceContext? = runCatching {
        PlanningSourceContext.parse(page.getJSONObject("source_context"))
    }.getOrNull()

    fun executionPageReady(page: JSONObject?): Boolean {
        if (page == null || page.optString("contract_version") != "2.0") return false
        val source = pageSource(page) ?: return false
        return page.objects("items").all { PlanningIntent.parseOrNull(it)?.sourceContext == source }
    }
}

object PlanningGateText {
    fun message(reasons: List<EligibilityReason>): String =
        (reasons.ifEmpty { listOf(EligibilityReason.INVALID_INTENT) })
            .distinct().joinToString("\n") { AppText.get(resource(it)) }

    private fun resource(reason: EligibilityReason): Int = when (reason) {
        EligibilityReason.EXECUTION_PATH_DISABLED -> R.string.planning_gate_execution_disabled
        EligibilityReason.LEGACY_READ_ONLY -> R.string.planning_legacy_read_only
        EligibilityReason.STALE_VERSION -> R.string.planning_gate_stale_version
        EligibilityReason.NOT_APPROVED -> R.string.planning_gate_not_approved
        EligibilityReason.RESEARCH_ONLY_MONTH -> R.string.planning_gate_research_only
        EligibilityReason.SUPERSEDED -> R.string.planning_gate_superseded
        EligibilityReason.WITHDRAWN -> R.string.planning_gate_withdrawn
        EligibilityReason.TERMINAL_STATE -> R.string.planning_gate_terminal
        EligibilityReason.UNKNOWN_EXECUTION_STATE -> R.string.planning_gate_unknown_execution
        EligibilityReason.ALREADY_PLACED -> R.string.planning_gate_already_placed
        EligibilityReason.OUTSIDE_EXECUTION_WINDOW -> R.string.planning_gate_outside_window
        EligibilityReason.MISSING_EXECUTION_WINDOW -> R.string.planning_gate_missing_window
        EligibilityReason.WRONG_ENVIRONMENT -> R.string.planning_gate_wrong_environment
        EligibilityReason.WRONG_ACCOUNT -> R.string.planning_gate_wrong_account
        EligibilityReason.WRONG_QUANTITY -> R.string.planning_gate_wrong_quantity
        EligibilityReason.WRONG_LIMIT_PRICE -> R.string.planning_gate_wrong_limit_price
        EligibilityReason.RECIPIENT_MISMATCH -> R.string.planning_gate_recipient_mismatch
        EligibilityReason.MISSING_ACCOUNT -> R.string.planning_gate_missing_account
        EligibilityReason.MISSING_RECIPIENT -> R.string.planning_gate_missing_recipient
        EligibilityReason.INVALID_INTENT -> R.string.planning_gate_invalid_intent
    }
}
