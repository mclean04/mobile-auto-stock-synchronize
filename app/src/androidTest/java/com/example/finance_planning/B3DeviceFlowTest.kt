package com.example.finance_planning

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finance_planning.core.*
import com.example.finance_planning.ui.ScreenState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs actual product Orders, ManualTradeDialog, PlanningRepository, Room/Vault,
 * HTTP parser/store/guard/report paths. Only identity, broker I/O and HTTP origin
 * are replaced. Firebase login/FCM/MainActivity navigation are outside this test.
 */
class B3DeviceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var f: B3DeviceFixture
    private lateinit var screen: MutableState<ScreenState>
    private lateinit var rootContent: @Composable () -> Unit
    private val refreshFailure = AtomicReference<Throwable?>()
    private fun text(id: Int) = compose.activity.getString(id)

    @Test fun deviceBusinessFlow() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, args.getString("b3_config", "b3-config.json"))
        val config = JSONObject(file.readText())
        val phase = requireNotNull(args.getString("b3_phase"))
        require(phase in setOf("readiness", "accepted", "resume_accepted", "late", "resume_late",
            "stale", "unknown", "kill_unknown", "resume_unknown", "concurrent"))
        f = B3DeviceFixture(context, config, phase)
        f.scenario = if (phase == "kill_unknown") "unknown" else phase.removePrefix("resume_")
        try {
            compose.activityRule.scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            if (config.has("service_url")) {
                val status = f.http("/qa/status")
                assertEquals("QA_ONLY", status.getString("mode"))
                assertEquals(config.getString("run_id"), status.getString("run_id"))
                assertEquals(config.getString("uid"), status.getString("actor_uid"))
                assertEquals(config.getString("account"), status.getString("account"))
                assertEquals("ANDROID_INJECTED_FAKE_ONLY", status.getString("broker"))
                assertEquals(0, status.getInt("clock_offset_seconds"))
                f.trace("native_http_readiness", JSONObject().put("armed", status.getBoolean("armed"))
                    .put("business_flow_run", false))
                if (phase == "readiness") {
                    f.trace("PASS", JSONObject().put("boundary", "native HTTP status only; no Google/business proof"))
                    return@runBlocking
                }
                check(status.getBoolean("armed")) { "BA has not armed the QA fixture" }
            } else require(phase != "readiness")
            f.saveFunds()
            val stored = f.repo.localPlanningAll()
            val funds = f.repo.dnseSnapshot()
            screen = mutableStateOf(ScreenState(planning = stored, dnse = funds,
                approved = true, admin = true, signedIn = true, hasDnse = true, dnseProduction = false))
            rootContent = {
                MaterialTheme {
                    val scope = rememberCoroutineScope()
                    Orders(screen.value, f.repo) {
                        screen.value = screen.value.copy(busy = true, planningExecutionFresh = false)
                        scope.launch {
                            try {
                                val all = f.repo.refreshPlanningAll()
                                screen.value = screen.value.copy(planning = all, planningExecutionFresh = true)
                            } catch (e: Throwable) { refreshFailure.set(e) }
                            finally { screen.value = screen.value.copy(busy = false) }
                        }
                    }
                }
            }
            compose.setContent(rootContent)
            compose.waitForIdle()
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals(0, f.listCalls.get())
            f.trace("local_start_no_list_request", JSONObject().put("cached", stored != null))
            if (phase.startsWith("resume_")) resume(phase)
            else if (phase == "concurrent") executeConcurrent()
            else execute(phase)
            f.trace("PASS", JSONObject().put("broker_calls", f.brokerCalls.get()).put("list_requests", f.listCalls.get()))
        } catch (e: Throwable) {
            if (::f.isInitialized && phase == "concurrent") {
                try { f.coordinatorAbort("participant_failed") } catch (_: Throwable) { }
            }
            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                repeat(roots.fetchSemanticsNodes().size) { i ->
                    f.trace("test_ui_failure", JSONObject().put("tree", roots[i].printToString()))
                }
            }
            f.trace("FAIL", JSONObject().put("type", e.javaClass.name).put("message", e.message))
            throw e
        } finally { f.close() }
    }

    private suspend fun executeConcurrent() {
        refresh()
        traceLocalTabs()
        openDialog()
        verifyReview()
        compose.onNodeWithText(text(R.string.trade_confirm_place)).performClick()
        val claimed = text(R.string.planning_execution_claimed_other_device)
        compose.waitUntil(90000) {
            f.brokerCalls.get() == 1 || compose.onAllNodesWithText(claimed).fetchSemanticsNodes().isNotEmpty()
        }
        val winner = f.brokerCalls.get() == 1
        if (winner) {
            compose.waitUntil(60000) {
                compose.onAllNodesWithText(text(R.string.trade_confirm_place)).fetchSemanticsNodes().isEmpty()
            }
            assertEquals("SUBMITTED", f.journal()!!.getString("state"))
            f.coordinatorEvent("PARTICIPANT_COMPLETE", JSONObject().put("result", "SUBMITTED"))
        } else {
            compose.onNodeWithText(claimed).assertIsDisplayed()
            assertNull(f.journal())
            assertEquals(0, f.brokerCalls.get())
            f.coordinatorEvent("PARTICIPANT_COMPLETE", JSONObject().put("result", "CLAIM_CONFLICT"))
        }
        f.trace("concurrent_confirmation_result", JSONObject().put("winner", winner)
            .put("broker_calls", f.brokerCalls.get()).put("journal", f.journal() ?: JSONObject.NULL))
    }

    private fun refresh() {
        val before = f.listCalls.get()
        compose.onNodeWithText(text(R.string.refresh_planning_orders)).performClick()
        compose.waitUntil(60000) { !screen.value.busy && (f.listCalls.get() > before || refreshFailure.get() != null) }
        refreshFailure.get()?.let { throw AssertionError("Manual refresh failed", it) }
        assertNotNull(screen.value.planning)
        assertTrue(screen.value.planningExecutionFresh)
        f.trace("manual_refresh_complete", JSONObject().put("requests", f.listCalls.get() - before)
            .put("snapshot", screen.value.planning))
    }

    private fun switchTab(id: Int) {
        compose.onNodeWithText(text(id)).performClick()
        compose.waitForIdle()
    }

    private suspend fun traceLocalTabs(recreate: Boolean = false) {
        val requests = f.listCalls.get()
        switchTab(R.string.planning_all_tab)
        compose.onAllNodesWithText(text(R.string.trade_title)).assertCountEquals(0)
        switchTab(R.string.history)
        compose.onAllNodesWithText(text(R.string.trade_title)).assertCountEquals(0)
        switchTab(R.string.pending)
        if (recreate) {
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                it.setContent(content = rootContent)
            }
        }
        compose.waitForIdle()
        assertEquals(requests, f.listCalls.get())
        val all = f.repo.localPlanningAll()!!
        val now = Instant.now()
        f.trace(if (recreate) "tabs_recreate_no_list_request" else "tabs_no_list_request",
            JSONObject().put("requests_before", requests)
            .put("requests_after", f.listCalls.get())
            .put("all", PlanningTimeline.rows(all, PlanningSection.ALL, now).size)
            .put("upcoming", PlanningTimeline.rows(all, PlanningSection.UPCOMING, now).size)
            .put("history", PlanningTimeline.rows(all, PlanningSection.HISTORY, now).size))
    }

    private fun openDialog() {
        val id = f.config.getJSONObject("intents").getString(f.scenario)
        val row = screen.value.planning!!.objects("items").single { it.getString("intent_id") == id }
        assertTrue(PlanningTimeline.mayOpenAction(PlanningSection.UPCOMING, row, Instant.now()))
        switchTab(R.string.pending)
        val tag = "planning-card-$id"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        val card = compose.onNodeWithTag(tag)
        if (card.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] ==
            text(R.string.planning_card_expand)) card.performClick()
        compose.onNode(hasText(text(R.string.trade_title)) and hasAnyAncestor(hasTestTag(tag)))
            .performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("QA cash account · " + f.config.getString("account")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun verifyReview() {
        compose.onNodeWithText("QA cash account · " + f.config.getString("account"))
            .performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("QA cash").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("QA cash").performScrollTo().performClick()
        compose.onAllNodesWithText("QA margin forbidden").assertCountEquals(0)
        compose.onNodeWithText(text(R.string.trade_review_button)).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText(text(R.string.trade_otp)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.trade_otp)).performScrollTo().performTextInput("123456")
        compose.onNodeWithText(text(R.string.trade_verify_otp)).performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText(text(R.string.trade_otp_ready)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, f.brokerCalls.get())
        f.trace("review_and_fake_otp_no_broker")
    }

    private suspend fun execute(phase: String) {
        refresh()
        traceLocalTabs()
        openDialog()
        verifyReview()
        // Closing after OTP/review still must not send an order.
        compose.onNodeWithText(text(R.string.close)).performClick()
        compose.waitForIdle()
        assertNull(f.journal())
        assertEquals(0, f.brokerCalls.get())
        f.trace("cancel_after_review_no_broker_or_marker")
        openDialog()
        verifyReview()
        f.dropNextAck = phase == "accepted"
        f.holdReports = phase == "late"
        f.brokerTimeout = phase == "unknown"
        f.killAfterMarker = phase == "kill_unknown"
        f.switchBeforeSource = phase == "stale"
        f.brokerRelease = CountDownLatch(1)
        compose.onNodeWithText(text(R.string.trade_confirm_place)).performTouchInput { doubleClick() }
        if (phase != "stale") compose.waitUntil(20000) { f.brokerCalls.get() == 1 }
        f.brokerRelease!!.countDown()
        compose.waitUntil(60000) {
            compose.onAllNodesWithText(text(R.string.trade_confirm_place)).fetchSemanticsNodes().isEmpty()
        }
        val journal = f.journal()!!
        assertEquals(if (phase == "stale") 0 else 1, f.brokerCalls.get())
        if (phase == "stale") {
            assertNotNull(f.lastSwitch)
            val originalSource = PlanningSourceContext.parse(journal.getJSONObject("source_context"))
            assertNotEquals(originalSource, PlanningContract.activeSource(f.lastActiveSource!!))
        }
        assertEquals(if (phase in setOf("stale", "unknown")) "UNKNOWN" else "SUBMITTED", journal.getString("state"))
        val reports = f.reports().filter { it.getJSONObject("payload").getString("intent_id") ==
            f.config.getJSONObject("intents").getString(f.scenario) }
        if (phase in setOf("accepted", "late")) {
            assertEquals(1, reports.size)
            assertEquals("PENDING", reports.single().getString("state"))
            File(f.directory, "$phase-payload.json").writeText(reports.single().getJSONObject("payload").toString())
        } else assertTrue(reports.isEmpty())
        f.trace("confirmation_result", JSONObject().put("phase", phase).put("journal", journal)
            .put("reports", org.json.JSONArray(reports)).put("broker_calls", f.brokerCalls.get()))
        if (phase == "late") {
            f.switchSource()
            f.trace("source_switched_with_immutable_pending_report", JSONObject().put("payload",
                reports.single().getJSONObject("payload")))
        }
    }

    private suspend fun resume(phase: String) {
        val all = f.repo.localPlanningAll()!!
        assertFalse(screen.value.planningExecutionFresh)
        traceLocalTabs(recreate = true)
        compose.onAllNodesWithText(text(R.string.trade_title)).assertCountEquals(0)
        assertEquals(0, f.brokerCalls.get())
        val journal = f.journal()!!
        if (phase == "resume_unknown") {
            assertEquals("UNKNOWN", journal.getString("state"))
            val plan = all.objects("items").single { it.getString("intent_id") ==
                f.config.getJSONObject("intents").getString("unknown") }
            val reopened = f.repo.manualTrade(plan, PlanningSection.UPCOMING)
            assertEquals("UNKNOWN", reopened.result()!!.getString("state"))
            reopened.close()
            assertEquals(0, f.brokerCalls.get())
            f.trace("unknown_reopened_no_broker_retry", JSONObject().put("journal", journal))
            return
        }
        val original = File(f.directory, "${f.scenario}-payload.json").readText()
        val requestId = JSONObject(original).getString("request_id")
        val queued = f.reports().single { it.getJSONObject("payload").getString("request_id") == requestId }
        assertEquals("PENDING", queued.getString("state"))
        assertEquals(original, queued.getJSONObject("payload").toString())
        f.repo.retryPlacedOrderReports()
        val reported = f.reports().single { it.getJSONObject("payload").getString("request_id") == requestId }
        assertEquals("REPORTED", reported.getString("state"))
        val ack = f.reportAcks.single { it.getString("request_id") == requestId }
        assertEquals(if (phase == "resume_late") "QUARANTINED_SOURCE_CHANGED" else "CURRENT_PRIMARY",
            ack.getJSONObject("report_destination").getString("state"))
        assertEquals(original, reported.getJSONObject("payload").toString())
        assertEquals(0, f.brokerCalls.get())
        assertEquals(0, f.listCalls.get())
        val readback = f.readback()
        if (f.config.has("service_url")) {
            val orderId = reported.getJSONObject("payload").getJSONObject("order").getString("order_id")
            var found = false
            for (alias in listOf("A", "B")) {
                val sheet = readback.getJSONObject(alias)
                assertTrue(sheet.getJSONArray("production_orders").length() <= 1)
                for (key in listOf("sandbox_orders", "sandbox_journal")) {
                    val rows = sheet.getJSONArray(key)
                    check(rows.length() < 100) { "Readback may be truncated; cannot prove absence" }
                    for (i in 0 until rows.length()) {
                        val cells = rows.getJSONArray(i)
                        if ((0 until cells.length()).any { cells.optString(it) == orderId }) found = true
                    }
                }
            }
            assertEquals("Native QA order presence", phase != "resume_late", found)
        }
        f.trace("pending_report_retried_immutable", JSONObject().put("report", reported)
            .put("journal", f.journal()).put("readback", readback))
    }
}
