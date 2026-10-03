package com.example.finance_planning.network

import com.example.finance_planning.network.session.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Tag
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Path
import retrofit2.http.Headers

class SessionRequestContext(private val current: () -> Boolean) {
    fun check() {
        val valid = try { current() } catch (_: Exception) { false }
        if (!valid) throw SupersededNetworkContext()
    }
}

interface BackendEndpoint {
    @Headers("Accept: application/json") @GET("health") suspend fun planningHealth(@Tag credentials: BackendCredentials): Response<PlanningHealthDto>
    @Headers("Accept: application/json") @GET("v1/sync/status") suspend fun planningSyncStatus(@Tag credentials: BackendCredentials): Response<PlanningSyncStatusDto>
    @Headers("Accept: application/json") @PUT("v1/devices/{device_id}") suspend fun planningRegisterDevice(@Path("device_id") id: String,
        @Body request: PlanningDeviceRequest, @Tag credentials: BackendCredentials): Response<PlanningDeviceDto>
    @Headers("Accept: application/json") @DELETE("v1/devices/{device_id}") suspend fun planningRemoveDevice(@Path("device_id") id: String,
        @Tag credentials: BackendCredentials): Response<PlanningDeviceDto>

    @POST("mobile/v2/session")
    suspend fun establishSession(@HeaderMap headers: Map<String, String>,
        @Tag context: SessionRequestContext,
        @Body request: SessionRegistrationRequest,
        @Tag credentials: BackendCredentials? = null): Response<BaseResponse<SessionRegistrationDto>>
}
