package com.example.finance_planning.sync

import android.content.Context
import androidx.work.*
import com.example.finance_planning.PlanningApp
import com.example.finance_planning.core.AppFailure
import com.example.finance_planning.network.HttpFailure
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as PlanningApp).repository
        if (!repo.approved()) return Result.failure()
        return try {
            val event = inputData.getString("event")
            if (event != null) {
                val expectedUid = inputData.getString("uid") ?: return Result.failure()
                if (repo.identity.uid() != expectedUid) return Result.failure()
                val notification = repo.receiveNotification(event)
                if (repo.identity.uid() != expectedUid || !repo.approved()) return Result.failure()
                val receipt = inputData.getString("receipt") ?: "RECEIVED"
                if (receipt == "RECEIVED" && !repo.notificationOpened(event))
                    PlanningMessagingService.show(applicationContext, notification)
                repo.api.receipt(event, repo.device(), receipt)
            } else {
                repo.registerPush()
                repo.notifications()
                if (repo.hasDnse()) repo.sync() else repo.retryPending()
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: HttpFailure) {
            if (e.status == 429 || e.status >= 500) Result.retry() else Result.failure()
        } catch (e: AppFailure) {
            if (e.retryable) Result.retry() else Result.failure()
        } catch (_: Exception) { Result.failure() }
    }
}
object SyncSchedule {
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun enable(context: Context) {
        val task = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("account-sync").build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("planning-periodic",
            ExistingPeriodicWorkPolicy.UPDATE, task)
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag("account-sync") }
    fun receipt(context: Context, event: String, state: String, uid: String) {
        val task = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints())
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 31)
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .setInputData(workDataOf("event" to event, "receipt" to state, "uid" to uid)).addTag("account-sync").build()
        WorkManager.getInstance(context).enqueueUniqueWork("receipt:$uid:$event:$state", ExistingWorkPolicy.KEEP, task)
    }
    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("planning-refresh", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).addTag("account-sync").build())
    }
}
