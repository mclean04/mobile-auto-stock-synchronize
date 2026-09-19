package com.example.finance_planning

import com.example.finance_planning.core.EligibilityReason
import com.example.finance_planning.core.PlanningActionPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningActionPolicyTest {
    private fun intent() = JSONObject()
        .put("contract_version", "2.0")
        .put("plan_id", "59c827db-79fa-4a56-943d-291831f28f51")
        .put("intent_id", "792f0b94-ad11-492b-b375-91916fa4ec68")
        .put("version", 3).put("authoring_state", "APPROVED").put("execution_state", "NOT_STARTED")
        .put("environment", "production").put("recipient_uid", "firebase-user").put("account", "012345")
        .put("symbol", "FPT").put("side", "BUY").put("quantity", "100")
        .put("limit_price_vnd", "90000").put("scheduled_at", "2026-10-01T09:00:00+07:00")
        .put("window_starts_at", "2026-10-01T09:00:00+07:00")
        .put("window_ends_at", "2026-10-01T14:30:00+07:00")
        .put("eligibility", JSONObject().put("eligible", true).put("reasons", JSONArray()))

    @Test fun cachedIntentNeverGrantsExecution() {
        val decision = PlanningActionPolicy.evaluate(intent(), activeProduction = true, freshForAction = false)
        assertFalse(decision.canExecute)
        assertTrue(decision.cachedReadOnly)
    }

    @Test fun onlyFreshMatchingEnvironmentCanExposeAction() {
        assertTrue(PlanningActionPolicy.evaluate(intent(), true, true).canExecute)
        val mismatch = PlanningActionPolicy.evaluate(intent(), false, true)
        assertFalse(mismatch.canExecute)
        assertEquals(listOf(EligibilityReason.WRONG_ENVIRONMENT), mismatch.reasons)
    }

    @Test fun legacyAndMalformedV2RemainReadOnly() {
        assertEquals(listOf(EligibilityReason.LEGACY_READ_ONLY),
            PlanningActionPolicy.evaluate(JSONObject().put("fields", JSONObject()), true, true).reasons)
        assertEquals(listOf(EligibilityReason.INVALID_INTENT),
            PlanningActionPolicy.evaluate(JSONObject().put("contract_version", "2.0"), true, true).reasons)
    }
}
