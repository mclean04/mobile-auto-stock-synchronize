package com.example.finance_planning

import com.example.finance_planning.core.PlanningFunds
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import org.json.JSONArray

class PlanningFundsTest {
    private fun plan(side: String = "MUA") = JSONObject("""{"fields":{"Mã":"REE","Mua/Bán":"$side","Số lượng":20,"Giá LO tối đa":45000,"Giá trị kế hoạch":900000,"Phí dự phòng":1800,"Tổng chi ngân sách":901800}}""")
    private fun snapshot(vararg cash: Long) = JSONObject().put("accounts", org.json.JSONArray(cash.indices.map {
        JSONObject().put("account", "$it").put("profile", JSONObject().put("dealAccount", true))
    })).put("balances", org.json.JSONArray(cash.mapIndexed { i, n -> JSONObject().put("account", "$i").put("cash_vnd", n).put("buying_power_vnd", 999999999) }))
    @Test fun feeReserveIsRequiredAndExactThresholdIsEnough() {
        assertEquals(BigDecimal("901800"), PlanningFunds.required(plan()))
        assertFalse(PlanningFunds.affordable(snapshot(901799), plan()))
        assertTrue(PlanningFunds.affordable(snapshot(901800), plan()))
    }
    @Test fun cannotCombineSubAccountsOrUseMarginBuyingPower() {
        assertFalse(PlanningFunds.affordable(snapshot(500000, 500000), plan()))
        assertFalse(PlanningFunds.affordable(snapshot(0), plan()))
        assertTrue(PlanningFunds.affordable(snapshot(0, 901800), plan()))
    }
    @Test fun missingBalanceAndMalformedBudgetFailClosedForBuys() {
        assertFalse(PlanningFunds.affordable(null, plan()))
        val missing = JSONObject("""{"fields":{"Mua/Bán":"MUA","Số lượng":20}}""")
        assertFalse(PlanningFunds.affordable(snapshot(999999999), missing))
        assertFalse(PlanningFunds.affordable(snapshot(999999999), plan("UNKNOWN")))
    }
    @Test fun sellDoesNotRequireCashButCancellationCannotPlace() {
        assertTrue(PlanningFunds.affordable(null, plan("BÁN")))
        val cancellation = plan().put("action", "CANCEL_ORDER")
        assertTrue(PlanningFunds.cancelled(cancellation))
        assertFalse(PlanningFunds.affordable(snapshot(999999999), cancellation))
    }
    @Test fun alteredDraftMustCoverHigherPrincipalAndScaledReserve() {
        assertEquals(BigDecimal("1803600"), PlanningFunds.required(plan(), BigDecimal("1800000")))
        assertEquals(BigDecimal("901800"), PlanningFunds.required(plan(), BigDecimal("100")))
        val understated = plan(); understated.getJSONObject("fields").put("Tổng chi ngân sách", 1)
        assertEquals(BigDecimal("901800"), PlanningFunds.required(understated))
    }
    @Test fun typedIntentWithoutCanonicalFeeBudgetFailsClosed() {
        val typed = JSONObject().put("contract_version", "2.0")
            .put("plan_id", "59c827db-79fa-4a56-943d-291831f28f51")
            .put("intent_id", "792f0b94-ad11-492b-b375-91916fa4ec68")
            .put("version", 3).put("authoring_state", "APPROVED").put("execution_state", "NOT_STARTED")
            .put("environment", "production").put("recipient_uid", "uid").put("account", "0")
            .put("symbol", "REE").put("side", "BUY").put("quantity", "20")
            .put("limit_price_vnd", "45000").put("scheduled_at", "2026-10-01T09:00:00+07:00")
            .put("window_starts_at", "2026-10-01T09:00:00+07:00")
            .put("window_ends_at", "2026-10-01T14:30:00+07:00")
            .put("eligibility", JSONObject().put("eligible", true).put("reasons", JSONArray()))
        assertEquals(BigDecimal("900000"), PlanningFunds.principal(typed))
        assertNull(PlanningFunds.required(typed))
        assertFalse(PlanningFunds.affordable(snapshot(999999999), typed))
    }
}
