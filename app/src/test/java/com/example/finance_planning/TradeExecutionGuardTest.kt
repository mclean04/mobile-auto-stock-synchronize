package com.example.finance_planning

import com.example.finance_planning.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TradeExecutionGuardTest {
    private fun intent(version: Int = 3, account: String = "012345", eligible: Boolean = true) = JSONObject()
        .put("contract_version", "2.0")
        .put("plan_id", "59c827db-79fa-4a56-943d-291831f28f51")
        .put("intent_id", "792f0b94-ad11-492b-b375-91916fa4ec68")
        .put("version", version).put("authoring_state", "APPROVED").put("execution_state", "NOT_STARTED")
        .put("environment", "production").put("recipient_uid", "firebase-user").put("account", account)
        .put("symbol", "FPT").put("side", "BUY").put("quantity", "100")
        .put("limit_price_vnd", "90000").put("scheduled_at", "2026-10-01T09:00:00+07:00")
        .put("window_starts_at", "2026-10-01T09:00:00+07:00")
        .put("window_ends_at", "2026-10-01T14:30:00+07:00")
        .put("eligibility", JSONObject().put("eligible", eligible).put("reasons",
            if (eligible) JSONArray() else JSONArray().put("NOT_APPROVED")))

    private fun preflight(version: Int = 3, eligible: Boolean = true) = JSONObject()
        .put("preflight_id", if (eligible) "c7e13b2f-0512-4947-b7ad-d0d9902a4d62" else JSONObject.NULL)
        .put("current_version", version).put("server_time", "2026-10-01T09:10:00+07:00")
        .put("preflight_expires_at", if (eligible) "2026-10-01T09:15:00+07:00" else JSONObject.NULL)
        .put("eligible", eligible).put("reasons",
            if (eligible) JSONArray() else JSONArray().put("STALE_VERSION"))

    @Test fun successfulTraceIsFreshReadThenPreflightThenPersistenceThenBroker() = runBlocking {
        val trace = mutableListOf<String>()
        val expected = PlanningIntent.parse(intent())
        val result = TradeExecutionGuard.execute(expected, "012345", Instant.now(),
            readCurrent = { trace += "fresh"; intent() },
            runPreflight = { request ->
                trace += "preflight"
                assertEquals(3, request.getInt("expected_version")); preflight()
            },
            beforeBrokerWrite = { trace += "persist:${it.preflightId}" },
            brokerWrite = { trace += "broker"; "order-42" })
        assertEquals("order-42", result.value)
        assertEquals(listOf("fresh", "preflight", "persist:c7e13b2f-0512-4947-b7ad-d0d9902a4d62", "broker"), trace)
    }

    @Test fun oldBackendOfflineMalformedAndMissingEligibilityNeverCallBroker() = runBlocking {
        val failures: List<suspend () -> JSONObject> = listOf(
            { throw java.io.IOException("offline") },
            { JSONObject().put("detail", "not found") },
            { intent().also { it.remove("eligibility") } }
        )
        for (read in failures) {
            var brokerCalls = 0
            assertThrows(PlanningPreflightUnavailable::class.java) { runBlocking {
                TradeExecutionGuard.execute(PlanningIntent.parse(intent()), "012345", Instant.now(), read,
                    { preflight() }, {}, { brokerCalls++; "never" })
            } }
            assertEquals(0, brokerCalls)
        }
    }

    @Test fun staleVersionAccountAndEnvironmentNeverReachPreflightOrBroker() = runBlocking {
        for (changed in listOf(intent(version = 4), intent(account = "999999"),
            intent().put("environment", "sandbox"))) {
            var preflightCalls = 0; var brokerCalls = 0
            assertThrows(PlanningVersionChanged::class.java) { runBlocking {
                TradeExecutionGuard.execute(PlanningIntent.parse(intent()), "012345", Instant.now(),
                    { changed }, { preflightCalls++; preflight() }, {}, { brokerCalls++; "never" })
            } }
            assertEquals(0, preflightCalls); assertEquals(0, brokerCalls)
        }
    }

    @Test fun negativeOrMalformedPreflightNeverCallsBroker() = runBlocking {
        for (response in listOf(preflight(eligible = false), preflight().also { it.remove("preflight_id") })) {
            var brokerCalls = 0
            assertThrows(Exception::class.java) { runBlocking {
                TradeExecutionGuard.execute(PlanningIntent.parse(intent()), "012345", Instant.now(),
                    { intent() }, { response }, {}, { brokerCalls++; "never" })
            } }
            assertEquals(0, brokerCalls)
        }
    }
}
