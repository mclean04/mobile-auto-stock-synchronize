package com.example.finance_planning

import com.example.finance_planning.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class IntegratedQaCompatibilityTest {
    private fun source() = JSONObject().put("source_id", "source-sheet-0001")
        .put("source_generation", 7)

    private fun cash() = JSONObject().put("currency", "VND")
        .put("principal_vnd", "9000000").put("fee_reserve_vnd", "18000")
        .put("required_cash_vnd", "9018000").put("fee_reserve_rate", "0.002")
        .put("policy_version", "cash-v1").put("cash_only", true)

    private fun intentJson() = JSONObject().put("contract_version", "2.0")
        .put("run_id", "integrated-qa-run")
        .put("plan_id", "59c827db-79fa-4a56-943d-291831f28f51")
        .put("intent_id", "792f0b94-ad11-492b-b375-91916fa4ec68")
        .put("version", 3).put("authoring_state", "APPROVED")
        .put("execution_state", "NOT_STARTED").put("environment", "sandbox")
        .put("recipient_uid", "firebase-user").put("account", "012345")
        .put("symbol", "FPT").put("side", "BUY").put("quantity", "100")
        .put("limit_price_vnd", "90000").put("scheduled_at", "2026-10-01T09:00:00+07:00")
        .put("window_starts_at", "2026-10-01T09:00:00+07:00")
        .put("window_ends_at", "2026-10-01T14:30:00+07:00")
        .put("source_context", source()).put("cash_requirements", cash())
        .put("eligibility", JSONObject().put("eligible", true).put("reasons", JSONArray()))

    @Test fun notificationReceiptsAndClaimConflictShareSafeLogWithoutBrokerFallback() = runBlocking {
        val root = Files.createTempDirectory("integrated-qa-observation").toFile()
        try {
            val log = ProductionObservationLog(File(root, "logs"))
            val intent = PlanningIntent.parse(intentJson())
            val requestId = "52efbda8-fc5f-447e-90cc-7bf4b92a2bf1"
            val correlation = ObservationCorrelation.intent(intent, requestId)
            var brokerCalls = 0
            val conflict = assertThrows(PlanningExecutionClaimed::class.java) { runBlocking {
                TradeExecutionGuard.execute(intent, "012345", Instant.now(),
                    readCurrent = { intentJson() },
                    runPreflight = { throw PlanningExecutionClaimed() },
                    beforeBrokerWrite = {},
                    readActiveSource = { JSONObject().put("contract_version", "2.0")
                        .put("state", "ACTIVE").put("source_context", source()) },
                    brokerWrite = { brokerCalls++; "forbidden" },
                    requestId = requestId,
                    deviceId = "41616ab6-282e-4234-aadd-6a1e7df4d2d9")
            } }
            assertTrue(log.record(ObservationComponent.TRADE, ObservationAction.PLACE_ORDER,
                ObservationStage.PREFLIGHT, ObservationResult.FAILED, correlation,
                error = ObservationError.fromThrowable(conflict)))

            val delivery = NotificationDelivery(
                NotificationEndpoint.QA, "de305d54-75b4-431b-adb2-eb6b9e546014", "firebase-user",
                "test-dnse-daily-20260925-1200", "3", "source-sheet-0001:7")
            val event = JSONObject().put("run_id", "integrated-qa-run")
                .put("plan_id", delivery.planId).put("version", 3)
                .put("source_context", source())
            val notification = ObservationCorrelation.notification(delivery, event)
            assertTrue(log.record(ObservationComponent.NOTIFICATION, ObservationAction.FCM_RECEIVE,
                ObservationStage.RECEIVED, ObservationResult.OBSERVED, notification))
            assertTrue(log.record(ObservationComponent.NOTIFICATION, ObservationAction.NOTIFICATION_RECEIPT,
                ObservationStage.OPENED, ObservationResult.SUCCEEDED, notification))

            assertEquals(0, brokerCalls)
            val winnerCorrelation = ObservationCorrelation.intent(intent, requestId, "B3-fake-order")
            assertEquals(requestId, winnerCorrelation.requestId)
            assertEquals("B3-fake-order", winnerCorrelation.brokerOrderId)
            val export = log.exportTo(File(root, "export"))!!
            val records = File(export.directory, "observation-0.jsonl").readLines()
                .filter(String::isNotBlank).map(::JSONObject)
            assertEquals(3, records.size)
            val claim = records.single { it.getString("stage") == "PREFLIGHT" }
            assertEquals(requestId, claim.getString("request_id"))
            assertEquals("EXECUTION_ALREADY_CLAIMED", claim.getString("error_code"))
            assertEquals(2, records.count { it.getString("component") == "NOTIFICATION" })
            assertTrue(records.all { it.getString("source_id") == "source-sheet-0001" })
            assertTrue(records.none { it.toString().contains("ANDROID_PRODUCTION") })
        } finally {
            root.deleteRecursively()
        }
    }
}
