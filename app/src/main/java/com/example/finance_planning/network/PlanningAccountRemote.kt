package com.example.finance_planning.network

import com.example.finance_planning.network.session.SessionGson
import retrofit2.Response

/** Four raw planning_backend contracts. No stock/mobile envelope, fallback or automatic replay. */
internal class PlanningAccountRemote(private val endpoint: BackendEndpoint, private val active: () -> Unit) {
    private suspend fun <T> execute(credentials: BackendCredentials, mutation: Boolean = false,
        request: suspend () -> Response<T>, validate: (T) -> T): T {
        val outcome = if (mutation) PlanningMutationOutcome.UNKNOWN else PlanningMutationOutcome.NOT_APPLICABLE
        active(); credentials.check()
        try {
            val response = request()
            try {
                if (!mutation) { active(); credentials.check() }
                if (!response.isSuccessful) {
                    val error = response.errorBody()?.use { body ->
                        try { SessionGson.gson.fromJson(body.charStream(), PlanningErrorDto::class.java)?.validated() }
                        catch (_: Exception) { null }
                    }
                    throw PlanningHttpFailure(response.code(), error, outcome)
                }
                protocol(response.code() == 200)
                val result = validate(response.body() ?: throw PlanningProtocolFailure())
                if (!mutation) { active(); credentials.check() }
                return result
            } finally { response.errorBody()?.close() }
        } catch (error: SupersededNetworkContext) { throw error
        } catch (error: java.io.IOException) { throw PlanningCallFailure(outcome, error)
        } catch (error: com.google.gson.JsonParseException) { throw PlanningCallFailure(outcome, error) }
    }
    suspend fun health() = BackendCredentials(emptyMap()).let { credentials ->
        execute(credentials, request = { endpoint.planningHealth(credentials) }, validate = PlanningHealthDto::validated)
    }
    suspend fun status(credentials: BackendCredentials) = execute(credentials,
        request = { endpoint.planningSyncStatus(credentials) }, validate = PlanningSyncStatusDto::validated)
    suspend fun register(id: String, request: PlanningDeviceRequest, credentials: BackendCredentials) = execute(credentials, true,
        request = { endpoint.planningRegisterDevice(id, request, credentials) }, validate = { it.validated(id, true) })
    suspend fun revoke(id: String, credentials: BackendCredentials) = execute(credentials, true,
        request = { endpoint.planningRemoveDevice(id, credentials) }, validate = { it.validated(id, false) })
}
