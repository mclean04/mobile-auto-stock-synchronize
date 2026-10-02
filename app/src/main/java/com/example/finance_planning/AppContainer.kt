package com.example.finance_planning

import android.content.Context
import androidx.room.Room
import com.example.finance_planning.auth.MobileIdentity
import com.example.finance_planning.core.ProductionObservationLog
import com.example.finance_planning.core.Vault
import com.example.finance_planning.data.LocalDb
import com.example.finance_planning.data.PlanningRepository
import com.example.finance_planning.network.*

/** Application composition only; keeps the existing SDK initialization order and legacy wire version. */
class AppContainer(context: Context) {
    val networks = NetworkClients.application
    private val identity = MobileIdentity(context).also { it.initialize() }
    val observationLog = ProductionObservationLog(
        java.io.File(context.noBackupFilesDir, ProductionObservationLog.DIRECTORY_NAME))
    private val vault = Vault(context)
    private val database = Room.databaseBuilder(context, LocalDb::class.java, "planning.db").build()
    // Lazy resolution is required: startup QA must never construct an ordinary Backend slot.
    private val backend = BackendApi(Transport(slotProvider = {
        networks.backend(BackendConfiguration(BackendWireVersion.LEGACY))
    }), identity::headers, requestContext = { path, method ->
        val uid = identity.uid()
        val device = uid?.let { vault.get("device:$it") }
        // A confirmed logout still needs its owner/device DELETE while its persistent fence is set.
        val revoke = method == "DELETE" && device != null && path == "/v1/devices/$device"
        SessionRequestContext {
            uid != null && identity.uid() == uid && vault.get("device:$uid") == device &&
                (revoke || vault.get("logout_pending:$uid") != "true")
        }
    })
    val repository = PlanningRepository(identity, vault, database, backend,
        stopAccountWork = { com.example.finance_planning.sync.SyncSchedule.cancelAccount(context) },
        networkClients = networks, observation = observationLog)
}
