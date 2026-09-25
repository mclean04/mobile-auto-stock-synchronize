package com.example.finance_planning

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.finance_planning.core.PlanningSection
import com.example.finance_planning.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Local composable tests only: never instantiate HTTP, DNSE, Firebase or a repository. */
class PlanningOrderCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var row by mutableStateOf(intent())
    private var owner by mutableStateOf("owner-a")
    private var section by mutableStateOf(PlanningSection.ALL)
    private var fresh by mutableStateOf(true)
    private var tick by mutableIntStateOf(0)
    private var showing by mutableStateOf(true)
    private var actions = 0
    private fun label(id: Int) = compose.activity.getString(id)
    private fun card() = compose.onNodeWithTag("planning-card-" + row.getString("intent_id"))
    private fun collapsed() {
        card().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label(R.string.planning_card_expand)))
        compose.onNodeWithText(label(R.string.trade_quantity)).assertDoesNotExist()
    }
    private fun expanded() = card().assert(SemanticsMatcher.expectValue(
        SemanticsProperties.StateDescription, label(R.string.planning_card_collapse)))
    private fun start() {
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.setContent {
            MaterialTheme {
                if (showing) {
                    val open = rememberPlanningCardExpansion(owner, row.optJSONObject("source_context"), section)
                    val id = planningCardIdentity(row)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Clock $tick")
                        key(id) {
                            PlanningOrderCard(row, null, section == PlanningSection.UPCOMING, true,
                                activeProduction = true, freshForAction = fresh,
                                expanded = open[id] == true, toggleExpanded = { open[id] = open[id] != true }) { actions++ }
                        }
                    }
                }
            }
        }
    }

    @Test fun everyTabStartsCollapsedAndRetainsSummaryAndReadonlyRules() {
        start()
        for (tab in PlanningSection.entries) {
            compose.runOnIdle { section = tab }
            collapsed()
            compose.onNodeWithText("FPT", substring = true).assertExists()
            compose.onNodeWithText("APPROVED · NOT_STARTED", substring = true).assertExists()
            compose.onNodeWithText(label(R.string.plan_sell_badge), substring = true).assertExists()
            compose.onNodeWithText(com.example.finance_planning.core.NotificationContent.time(row.getString("scheduled_at")), substring = true).assertExists()
            card().performClick()
            expanded()
            compose.onNodeWithText(label(R.string.trade_quantity)).assertExists()
            if (tab != PlanningSection.UPCOMING) compose.onNodeWithText(label(R.string.trade_title)).assertDoesNotExist()
            card().performClick()
            collapsed()
        }
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun clockAndSameIdentityRefreshRetainExpansionButScopesAndScreenResetIt() {
        start()
        card().performClick()
        repeat(3) {
            compose.runOnIdle { tick++; row = JSONObject(row.toString()).put("version", 4 + it) }
            expanded()
        }
        compose.runOnIdle { row = JSONObject(row.toString()).put("intent_id", "192f0b94-ad11-492b-b375-91916fa4ec68") }
        collapsed()
        card().performClick()
        compose.runOnIdle { row = JSONObject(row.toString()).apply { getJSONObject("source_context").put("source_generation", 8) } }
        collapsed()
        card().performClick()
        compose.runOnIdle { row = JSONObject(row.toString()).apply { getJSONObject("source_context").put("source_id", "source-sheet-0002") } }
        collapsed()
        card().performClick()
        compose.runOnIdle { owner = "owner-b" }
        collapsed()
        card().performClick()
        compose.runOnIdle { showing = false }
        compose.waitForIdle()
        compose.runOnIdle { showing = true }
        collapsed()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun childActionConsumesTapAndCollapsedOrCachedCardCannotTriggerOrder() {
        section = PlanningSection.UPCOMING
        start()
        collapsed()
        compose.onNodeWithText(label(R.string.trade_title)).assertDoesNotExist()
        card().performClick()
        expanded()
        compose.runOnIdle { assertEquals(0, actions) }
        compose.onNodeWithText(label(R.string.trade_title)).performScrollTo().performTouchInput { click() }
        expanded()
        compose.runOnIdle { assertEquals(1, actions) }
        card().performClick()
        collapsed()
        compose.runOnIdle { fresh = false }
        card().performClick()
        expanded()
        compose.onNodeWithText(label(R.string.trade_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, actions) }
    }

    private fun intent() = JSONObject()
        .put("contract_version", "2.0")
        .put("plan_id", "59c827db-79fa-4a56-943d-291831f28f51")
        .put("intent_id", "792f0b94-ad11-492b-b375-91916fa4ec68")
        .put("record_kind", "CANONICAL").put("time_status", "EXACT")
        .put("version", 3).put("authoring_state", "APPROVED").put("execution_state", "NOT_STARTED")
        .put("environment", "production").put("recipient_uid", "firebase-user").put("account", "012345")
        .put("symbol", "FPT").put("side", "SELL").put("quantity", "100")
        .put("limit_price_vnd", "90000").put("scheduled_at", "2026-10-01T09:00:00+07:00")
        .put("window_starts_at", "2026-10-01T09:00:00+07:00")
        .put("window_ends_at", "2026-10-01T14:30:00+07:00")
        .put("source_context", JSONObject().put("source_id", "source-sheet-0001").put("source_generation", 7))
        .put("cash_requirements", JSONObject().put("currency", "VND")
            .put("principal_vnd", "9000000").put("fee_reserve_vnd", "18000")
            .put("required_cash_vnd", "0").put("fee_reserve_rate", "0.002")
            .put("policy_version", "cash-v1").put("cash_only", true))
        .put("eligibility", JSONObject().put("eligible", true).put("reasons", JSONArray()))

}
