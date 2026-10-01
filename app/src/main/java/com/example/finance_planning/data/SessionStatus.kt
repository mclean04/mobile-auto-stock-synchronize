package com.example.finance_planning.data

import com.example.finance_planning.network.session.SessionRole
import com.example.finance_planning.network.session.SyncStatusDto
import org.json.JSONObject

data class TokenRefreshTicket(val uid: String, val deviceId: String, val eventId: String)

data class SessionStatus(val pendingSheetBatches: Int, val countCappedAt: Int,
                         val sheetWrites: Boolean?, val admin: Boolean, val qaIsolated: Boolean = false) {
    companion object {
        fun from(dto: SyncStatusDto) = SessionStatus(dto.pending_sheet_batches, dto.count_capped_at,
            dto.sheet_writes, dto.role == SessionRole.admin)
        // Compatibility mapping is confined to the existing legacy endpoint.
        fun legacy(json: JSONObject) = SessionStatus(json.optInt("pending_sheet_batches"),
            json.optInt("count_capped_at", 200), json.opt("sheet_writes") as? Boolean,
            json.optString("role") == "admin")
    }
}
