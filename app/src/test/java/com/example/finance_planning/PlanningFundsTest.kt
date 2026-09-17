package com.example.finance_planning

import com.example.finance_planning.core.PlanningFunds
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

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
}
