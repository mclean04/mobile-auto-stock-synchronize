package com.example.finance_planning.core

import org.json.JSONObject
import java.time.Duration
import java.time.Instant

class PlanningPreflightUnavailable(cause: Throwable? = null) : Exception("Planning preflight unavailable", cause)
class PlanningGateFailure(val reasons: List<EligibilityReason>) : Exception("Planning execution blocked")
class PlanningVersionChanged : Exception("Planning version changed")

data class GuardedBrokerResult<T>(val value: T, val preflight: PreflightAuthorization)

/** The only path from a canonical intent to a broker mutation in the manual Production flow. */
object TradeExecutionGuard {
    suspend fun <T> execute(
        expected: PlanningIntent,
        account: String,
        observedAt: Instant,
        readCurrent: suspend () -> JSONObject,
        runPreflight: suspend (JSONObject) -> JSONObject,
        beforeBrokerWrite: suspend (PreflightAuthorization) -> Unit,
        readActiveSource: suspend () -> JSONObject,
        brokerWrite: suspend () -> T,
        clock: () -> Instant = Instant::now,
        monotonicNanos: () -> Long = System::nanoTime
    ): GuardedBrokerResult<T> {
        val current = try { PlanningIntent.parse(readCurrent()) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { throw PlanningPreflightUnavailable(e) }
        if (!sameExecutionContract(expected, current) || current.account != account)
            throw PlanningVersionChanged()

        // Start the monotonic validity budget before the request so transport delay can only
        // shorten a grant. Server time defines the budget; the device clock is an extra fail-safe.
        val preflightStartedAt = monotonicNanos()
        val preflight = try {
            PreflightAuthorization.parse(runPreflight(
                PlanningContract.preflightRequest(current, account, observedAt)), current)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw PlanningPreflightUnavailable(e) }

        if (preflight.intentId != current.intentId || preflight.currentVersion != current.version)
            throw PlanningVersionChanged()
        if (preflight.sourceContext != current.sourceContext ||
            !preflight.cashRequirements.sameAs(current.cashRequirements))
            throw PlanningVersionChanged()
        if (!preflight.eligibility.eligible)
            throw PlanningGateFailure(preflight.eligibility.reasons)
        val validityNanos = preflight.expiresAt?.let {
            runCatching { Duration.between(preflight.serverTime, it).toNanos() }.getOrNull()
        }
        fun authorizationStillValid(): Boolean {
            val elapsedNanos = monotonicNanos() - preflightStartedAt
            return preflight.preflightId != null && preflight.expiresAt != null &&
                validityNanos != null && validityNanos > 0 &&
                elapsedNanos in 0 until validityNanos && clock().isBefore(preflight.expiresAt)
        }
        if (!authorizationStillValid())
            throw PlanningPreflightUnavailable()

        beforeBrokerWrite(preflight)
        // Local durable persistence can cross the grant boundary. Recheck after it so the
        // following statement remains the only broker mutation and never uses an expired grant.
        if (!authorizationStillValid())
            throw PlanningPreflightUnavailable()
        val activeSource = try { PlanningContract.activeSource(readActiveSource()) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { throw PlanningPreflightUnavailable(e) }
        if (activeSource != current.sourceContext) throw PlanningVersionChanged()
        if (!authorizationStillValid()) throw PlanningPreflightUnavailable()
        return GuardedBrokerResult(brokerWrite(), preflight)
    }

    internal fun sameExecutionContract(expected: PlanningIntent, current: PlanningIntent): Boolean =
        expected.intentId == current.intentId && expected.planId == current.planId &&
            expected.version == current.version && expected.authoringState == current.authoringState &&
            expected.executionState == current.executionState && expected.environment == current.environment &&
            expected.recipientUid == current.recipientUid && expected.account == current.account &&
            expected.symbol == current.symbol && expected.side == current.side &&
            expected.quantity == current.quantity && expected.limitPriceVnd == current.limitPriceVnd &&
            expected.sourceContext == current.sourceContext &&
            expected.cashRequirements.sameAs(current.cashRequirements) &&
            expected.windowStartsAt == current.windowStartsAt && expected.windowEndsAt == current.windowEndsAt
}
