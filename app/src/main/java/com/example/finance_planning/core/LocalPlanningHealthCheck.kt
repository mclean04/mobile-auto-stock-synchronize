package com.example.finance_planning.core

import com.example.finance_planning.R
import com.example.finance_planning.network.*
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** UI diagnostic for an explicit local health GET only; never establishes or replays a session. */
object LocalPlanningHealthCheck {
    suspend fun check(health: suspend () -> PlanningHealthDto): String = try {
        health().validated()
        AppText.get(R.string.local_planning_health_ok, Contracts.BACKEND)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SupersededNetworkContext) {
        throw error
    } catch (error: HttpFailure) {
        AppText.get(R.string.local_planning_health_http, Contracts.BACKEND, error.status)
    } catch (error: IOException) {
        var cause: Throwable? = error
        var incompatible = false
        repeat(8) {
            if (cause is PlanningProtocolFailure || cause is JsonParseException ||
                cause is com.google.gson.stream.MalformedJsonException) incompatible = true
            cause = cause?.cause
        }
        AppText.get(if (incompatible) R.string.local_planning_health_incompatible
            else R.string.local_planning_health_connection, Contracts.BACKEND)
    } catch (_: JsonParseException) {
        AppText.get(R.string.local_planning_health_incompatible, Contracts.BACKEND)
    }
}
