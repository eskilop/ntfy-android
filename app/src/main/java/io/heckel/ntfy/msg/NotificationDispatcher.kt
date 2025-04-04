package io.heckel.ntfy.msg

import android.content.Context
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.log.Log
import io.heckel.ntfy.up.Distributor
import io.heckel.ntfy.util.safeLet

/**
 * The notification dispatcher figures out what to do with a notification.
 * It may display a notification, send out a broadcast, or forward via UnifiedPush.
 */
class NotificationDispatcher(val context: Context, val repository: Repository) {
    private val notifier = NotificationService(context)
    private val broadcaster = BroadcastService(context)
    private val distributor = Distributor(context)

    fun init() {
        notifier.createNotificationChannels()
    }

    fun dispatch(subscription: Subscription, notification: Notification) {
        Log.d(TAG, "Dispatching $notification for subscription $subscription")

        val muted = getMuted(subscription)
        val notify = shouldNotify(subscription, notification, muted)
        val broadcast = shouldBroadcast(subscription)
        val distribute = shouldDistribute(subscription)
        val download = shouldDownload(notification)
        if (notify) {
            notifier.display(subscription, notification)
        }
        if (broadcast) {
            broadcaster.send(subscription, notification, muted)
        }
        if (distribute) {
            safeLet(subscription.upAppId, subscription.upConnectorToken) { appId, connectorToken ->
                distributor.sendMessage(appId, connectorToken, notification.message)
            }
        }
        if (download) {
            DownloadManager.enqueue(context, notification.id, userAction = false)
        }
    }

    private fun shouldDownload(notification: Notification): Boolean {
        if (notification.attachment == null) {
            return false
        }
        val maxAutoDownloadSize = repository.getAutoDownloadMaxSize()
        when (maxAutoDownloadSize) {
            Repository.AUTO_DOWNLOAD_ALWAYS -> return true
            Repository.AUTO_DOWNLOAD_NEVER -> return false
            else -> {
                if (notification.attachment.size == null) {
                    return true // DownloadWorker will bail out if attachment is too large!
                }
                return notification.attachment.size <= maxAutoDownloadSize
            }
        }
    }

    private fun shouldNotify(subscription: Subscription, notification: Notification, muted: Boolean): Boolean {
        if (subscription.upAppId != null) {
            return false
        }
        val priority = if (notification.priority > 0) notification.priority else 3
        if (priority < repository.getMinPriority()) {
            return false
        }
        val detailsVisible = repository.detailViewSubscriptionId.get() == notification.subscriptionId
        return !detailsVisible && !muted
    }

    private fun shouldBroadcast(subscription: Subscription): Boolean {
        if (subscription.upAppId != null) { // Never broadcast for UnifiedPush subscriptions
            return false
        }
        return repository.getBroadcastEnabled()
    }

    private fun shouldDistribute(subscription: Subscription): Boolean {
        return subscription.upAppId != null // Only distribute for UnifiedPush subscriptions
    }

    private fun getMuted(subscription: Subscription): Boolean {
        if (repository.isGlobalMuted()) {
            return true
        }
        return subscription.mutedUntil == 1L || (subscription.mutedUntil > 1L && subscription.mutedUntil > System.currentTimeMillis()/1000)
    }

    companion object {
        private const val TAG = "NtfyNotifDispatch"
    }
}
