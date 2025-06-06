package io.heckel.ntfy.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.db.Database
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.log.Log
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // IMPORTANT WARNING:
    //   Every time the worker is changed, the periodic work has to be REPLACEd.
    //   This is facilitated in the MainActivity using the VERSION below.

    init {
        Log.init(ctx) // Init in all entrypoints
    }

    override suspend fun doWork(): Result {

        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Polling for new notifications")
            val database = Database.getInstance(applicationContext)
            val sharedPrefs = applicationContext.getSharedPreferences(Repository.SHARED_PREFS_ID, Context.MODE_PRIVATE)
            val repository = Repository.getInstance(sharedPrefs, database.subscriptionDao(), database.notificationDao())
            val dispatcher = NotificationDispatcher(applicationContext, repository)
            val api = ApiService()

            repository.getSubscriptions().forEach{ subscription ->
                try {
                    val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic, since = subscription.lastActive)
                    val newNotifications = repository
                        .onlyNewNotifications(subscription.id, notifications)
                        .map { it.copy(notificationId = Random.nextInt()) }
                    newNotifications.forEach { notification ->
                        if (repository.addNotification(notification)) {
                            dispatcher.dispatch(subscription, notification)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed checking messages: ${e.message}", e)
                }
            }
            Log.d(TAG, "Finished polling for new notifications")
            return@withContext Result.success()
        }
    }

    companion object {
        const val VERSION =  BuildConfig.VERSION_CODE
        const val TAG = "NtfyPollWorker"
        const val WORK_NAME_PERIODIC = "NtfyPollWorkerPeriodic" // Do not change
    }
}
