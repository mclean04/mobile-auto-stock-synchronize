package com.example.finance_planning.network.session

import java.io.IOException

data class SessionRegistrationRequest(val device_id: String, val fcm_token: String) {
    override fun toString() = "SessionRegistrationRequest(redacted)"
}
data class BaseResponse<T>(val code: Int, val data: T?, val error: ApiError?, val meta: ResponseMeta)
data class SessionRegistrationDto(val session: SyncStatusDto, val registration: DeviceRegistrationDto)
data class SyncStatusDto(val pending_sheet_batches: Int, val count_capped_at: Int,
                         val sheet_writes: Boolean, val role: SessionRole)
enum class SessionRole { admin, uploader }
data class DeviceRegistrationDto(val device_id: String, val registered: Boolean)
data class ResponseMeta(val contract_version: String, val request_id: String,
                        val http_status: Int, val pagination: Nothing?)
data class ApiError(val code: String, val message: String, val validation: List<ValidationIssue>,
                    val operation: OperationOutcome?)
data class ValidationIssue(val location: IssueLocation, val pointer: String, val code: String, val message: String)
enum class IssueLocation { body, query, path, header }
enum class DatabaseOutcome { NOT_APPLIED, COMMITTED, UNKNOWN }
enum class ProjectionOutcome { NOT_APPLICABLE, PENDING, UP_TO_DATE, QUARANTINED, UNKNOWN }
enum class TradingEnvironment { sandbox, production }
// This endpoint only commits registration; other operations keep their existing DTO family.
enum class SessionCommittedStep { DEVICE_REGISTRATION }
data class OperationOutcome(val operation_id: String?, val database: DatabaseOutcome,
                            val projection: ProjectionOutcome, val environment: TradingEnvironment?,
                            val committed_steps: List<SessionCommittedStep>)
class SessionProtocolFailure : IOException("Invalid session response")
class SessionServerFailure(val response: BaseResponse<SessionRegistrationDto>) :
    Exception("Session HTTP ${response.code}")
