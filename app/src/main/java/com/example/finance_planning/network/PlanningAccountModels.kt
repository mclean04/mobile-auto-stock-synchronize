package com.example.finance_planning.network

/** Nullable wire fields allow Gson to distinguish absent required values from primitive defaults. */
data class PlanningHealthDto(val status: String?, val storage: String?) {
    fun validated() = apply { protocol(status == "ok" && storage == "firestore") }
}
data class PlanningSyncStatusDto(val pending_sheet_batches: Int?, val count_capped_at: Int?,
                                 val sheet_writes: Boolean?, val role: String?) {
    fun validated() = apply {
        protocol(pending_sheet_batches != null && pending_sheet_batches in 0..200)
        protocol(count_capped_at == 200 && sheet_writes != null && role in setOf("admin", "uploader"))
    }
}
data class PlanningDeviceRequest(val fcm_token: String) {
    init { require(fcm_token.codePointCount(0, fcm_token.length) in 20..4096) }
    override fun toString() = "PlanningDeviceRequest(redacted)"
}
data class PlanningDeviceDto(val device_id: String?, val registered: Boolean?) {
    fun validated(expected: String, registration: Boolean) = apply {
        protocol(device_id == expected && registered == registration)
    }
}
data class PlanningErrorDto(val detail: String?) {
    fun validated() = apply { protocol(detail != null) }
}
enum class PlanningMutationOutcome { NOT_APPLICABLE, UNKNOWN }
class PlanningProtocolFailure : java.io.IOException("Invalid planning response")
class PlanningCallFailure(val outcome: PlanningMutationOutcome, cause: Exception) :
    java.io.IOException("Planning request failed", cause)
class PlanningHttpFailure(status: Int, val error: PlanningErrorDto?, val outcome: PlanningMutationOutcome) :
    HttpFailure(status, error?.detail?.takeIf { Regex("[a-z][a-z0-9_]{0,99}").matches(it) })
internal fun protocol(valid: Boolean) { if (!valid) throw PlanningProtocolFailure() }
