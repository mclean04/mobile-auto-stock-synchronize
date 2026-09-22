package com.example.finance_planning.sync

import android.content.Context
import androidx.work.*
import com.example.finance_planning.PlanningApp
import com.example.finance_planning.core.AppFailure
import com.example.finance_planning.core.NotificationDelivery
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
                val delivery = inputData.getString("delivery")?.let {
                    runCatching { NotificationDelivery.parse(org.json.JSONObject(it)) }.getOrNull()
                } ?: return Result.failure()
                if (delivery.eventId != event || delivery.targetUid != expectedUid) return Result.failure()
                val receipt = inputData.getString("receipt") ?: "RECEIVED"
                val notification = repo.receiveNotification(event, delivery, receipt == "RECEIVED")
                if (repo.identity.uid() != expectedUid || !repo.approved()) return Result.failure()
                if (receipt == "RECEIVED" && !repo.notificationOpened(event)) {
                    PlanningMessagingService.show(applicationContext, notification)
                }
                repo.notificationReceipt(delivery, receipt)
                repo.notifications()
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
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("planning-periodic") }
    fun cancelAccount(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag("account-sync") }
    fun receipt(context: Context, delivery: NotificationDelivery, state: String) {
        val task = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints())
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 31)
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .setInputData(workDataOf("event" to delivery.eventId, "receipt" to state,
                "uid" to delivery.targetUid, "delivery" to delivery.json().toString()))
            .addTag("account-sync").build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "receipt:${delivery.endpoint}:${delivery.targetUid}:${delivery.eventId}:$state",
            ExistingWorkPolicy.KEEP, task)
    }
    fun console(context: Context, uid: String, message: String, title: String, body: String): org.json.JSONObject {
        val id = java.util.UUID.nameUUIDFromBytes(message.toByteArray(Charsets.UTF_8)).toString()
        val event = org.json.JSONObject().put("event_id", id).put("title", title.take(500))
            .put("body", body.take(2000)).put("created_at", java.time.Instant.now().toString()).put("local_only", true)
        WorkManager.getInstance(context).enqueueUniqueWork("console:$uid:$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ConsoleNotificationWorker>()
                .setInputData(workDataOf("uid" to uid, "event" to id,
                    "title" to event.getString("title"), "body" to event.getString("body"),
                    "created_at" to event.getString("created_at")))
                .addTag("account-sync").build())
        return event
    }
    fun notificationRefresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("notification-refresh", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<NotificationRefreshWorker>().setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf("uid" to (context.applicationContext as PlanningApp).repository.identity.uid()))
                .addTag("account-sync").build())
    }
    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("planning-refresh", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).addTag("account-sync").build())
    }
}

/** Saves Console notification content even when the network is unavailable. */
class ConsoleNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as PlanningApp).repository
        val uid = inputData.getString("uid") ?: return Result.failure()
        if (repo.identity.uid() != uid || !repo.approved()) return Result.failure()
        val event = org.json.JSONObject().put("event_id", inputData.getString("event"))
            .put("title", inputData.getString("title")).put("body", inputData.getString("body"))
            .put("created_at", inputData.getString("created_at")).put("local_only", true)
        repo.cacheNotifications(org.json.JSONObject().put("items", org.json.JSONArray().put(event)), uid)
        if (!repo.notificationOpened(event.getString("event_id"))) {
            PlanningMessagingService.show(applicationContext, event)
        }
        SyncSchedule.notificationRefresh(applicationContext)
        return Result.success()
    }
}
class NotificationRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as PlanningApp).repository
        val uid = inputData.getString("uid") ?: return Result.failure()
        if (repo.identity.uid() != uid || !repo.approved()) return Result.failure()
        return try {
            repo.notifications()
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: HttpFailure) { if (e.status == 429 || e.status >= 500) Result.retry() else Result.failure() }
        catch (e: AppFailure) { if (e.retryable) Result.retry() else Result.failure() }
        catch (_: Exception) { Result.retry() }
    }
}
