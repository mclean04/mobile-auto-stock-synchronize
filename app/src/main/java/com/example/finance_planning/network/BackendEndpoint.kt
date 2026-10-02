package com.example.finance_planning.network

import com.example.finance_planning.network.session.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Tag

class SessionRequestContext(private val current: () -> Boolean) {
    fun check() {
        val valid = try { current() } catch (_: Exception) { false }
        if (!valid) throw SupersededNetworkContext()
    }
}

interface BackendEndpoint {
    @POST("mobile/v2/session")
    suspend fun establishSession(@HeaderMap headers: Map<String, String>,
        @Tag context: SessionRequestContext,
        @Body request: SessionRegistrationRequest,
        @Tag credentials: BackendCredentials? = null): Response<BaseResponse<SessionRegistrationDto>>
}
