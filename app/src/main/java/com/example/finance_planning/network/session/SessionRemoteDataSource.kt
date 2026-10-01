package com.example.finance_planning.network.session

import com.example.finance_planning.network.BackendEndpoint
import com.example.finance_planning.network.SessionRequestContext

/** Exactly one request; no refresh, legacy fallback or retry of an uncertain registration. */
class SessionRemoteDataSource(private val endpoint: BackendEndpoint,
                              private val headers: suspend () -> Map<String, String>) {
    suspend fun establish(request: SessionRegistrationRequest, context: SessionRequestContext): SessionRegistrationDto {
        context.check()
        val auth = headers()
        context.check()
        val response = endpoint.establishSession(auth + ("Accept" to "application/json"), context, request)
        val body = try {
            context.check()
            if (response.isSuccessful) response.body() ?: throw SessionProtocolFailure()
            else response.errorBody()?.let(SessionGson::decode) ?: throw SessionProtocolFailure()
        } finally { response.errorBody()?.close() }
        if (body.code != response.code() || body.meta.request_id != response.headers()["X-Request-ID"] ||
            body.meta.http_status != response.code()) throw SessionProtocolFailure()
        val id = body.data?.registration?.device_id ?: body.error?.operation?.operation_id
        if (id != null && !id.equals(request.device_id, ignoreCase = true)) throw SessionProtocolFailure()
        context.check()
        if (body.error != null) throw SessionServerFailure(body)
        return body.data ?: throw SessionProtocolFailure()
    }
}
