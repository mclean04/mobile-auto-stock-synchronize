package com.example.finance_planning

import android.app.Application
import com.example.finance_planning.data.PlanningRepository

class PlanningApp : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        com.example.finance_planning.core.QaStartupIsolation.attach(this)
    }
    lateinit var repository: PlanningRepository
        private set
    lateinit var observationLog: com.example.finance_planning.core.ProductionObservationLog
        private set
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        com.example.finance_planning.core.AppText.initialize(this)
        com.example.finance_planning.sync.PlanningMessagingService.createChannel(this)
        container = AppContainer(this)
        observationLog = container.observationLog
        repository = container.repository
    }
}
