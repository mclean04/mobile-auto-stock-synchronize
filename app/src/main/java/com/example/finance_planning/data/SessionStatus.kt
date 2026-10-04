package com.example.finance_planning.data

import com.example.finance_planning.network.session.SessionRole
import com.example.finance_planning.network.session.SyncStatusDto

data class TokenRefreshTicket(val uid: String, val deviceId: String, val eventId: String)

data class SessionStatus(val pendingSheetBatches: Int, val countCappedAt: Int,
                         val sheetWrites: Boolean?, val admin: Boolean, val qaIsolated: Boolean = false) {
    companion object {
        fun from(dto: SyncStatusDto) = SessionStatus(dto.pending_sheet_batches, dto.count_capped_at,
            dto.sheet_writes, dto.role == SessionRole.admin)
        fun from(dto: com.example.finance_planning.network.PlanningSyncStatusDto): SessionStatus {
            dto.validated()
            return SessionStatus(requireNotNull(dto.pending_sheet_batches), requireNotNull(dto.count_capped_at),
                requireNotNull(dto.sheet_writes), dto.role == "admin")
        }
    }
}
