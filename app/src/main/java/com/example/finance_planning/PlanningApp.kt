package com.example.finance_planning

import android.app.Application
import androidx.room.Room
import com.example.finance_planning.auth.MobileIdentity
import com.example.finance_planning.core.Vault
import com.example.finance_planning.data.LocalDb
import com.example.finance_planning.data.PlanningRepository
import com.example.finance_planning.network.BackendApi
import com.example.finance_planning.network.Transport

class PlanningApp : Application() {
    lateinit var repository: PlanningRepository
        private set
    override fun onCreate() {
        super.onCreate()
        com.example.finance_planning.core.AppText.initialize(this)
        com.example.finance_planning.sync.PlanningMessagingService.createChannel(this)
        val identity = MobileIdentity(this)
        identity.initialize()
        repository = PlanningRepository(identity, Vault(this),
            Room.databaseBuilder(this, LocalDb::class.java, "planning.db").build(),
            BackendApi(Transport(), identity::headers))
    }
}
