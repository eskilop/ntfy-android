package io.heckel.ntfy.firebase

import android.content.Intent
import android.util.Base64
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Attachment
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.log.Log
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.MESSAGE_ENCODING_BASE64
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.util.toPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

class FirebaseService : FirebaseMessagingService() {
    private val repository by lazy { (application as Application).repository }
    private val dispatcher by lazy { NotificationDispatcher(this, repository) }
    private val job = SupervisorJob()
    private val messenger = FirebaseMessenger()

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Init log (this is done in all entrypoints)
        Log.init(this)

        // We only process data messages
        if (remoteMessage.data.isEmpty()) {
            Log.d(TAG, "Discarding unexpected message (1): from=${remoteMessage.from}")
            return
        }

        // Dispatch event
        val data = remoteMessage.data
        when (data["event"]) {
            ApiService.EVENT_KEEPALIVE -> handleKeepalive(remoteMessage)
            ApiService.EVENT_MESSAGE -> handleMessage(remoteMessage)
            else -> Log.d(TAG, "Discarding unexpected message (2): from=${remoteMessage.from}, data=${data}")
        }
    }

    private fun handleKeepalive(remoteMessage: RemoteMessage) {
        Log.d(TAG, "Keepalive received, sending auto restart broadcast for foregrounds service")
        sendBroadcast(Intent(this, SubscriberService.AutoRestartReceiver::class.java)) // Restart it if necessary!
        val topic = remoteMessage.data["topic"]
        if (topic != ApiService.CONTROL_TOPIC) {
            Log.d(TAG, "Keepalive on non-control topic $topic received, subscribing to control topic ${ApiService.CONTROL_TOPIC}")
            messenger.subscribe(ApiService.CONTROL_TOPIC)
        }
    }

    private fun handleMessage(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val id = data["id"]
        val timestamp = data["time"]?.toLongOrNull()
        val topic = data["topic"]
        val title = data["title"]
        val message = data["message"]
        val priority = data["priority"]?.toIntOrNull()
        val tags = data["tags"]
        val click = data["click"]
        val encoding = data["encoding"]
        val attachmentName = data["attachment_name"] ?: "attachment.bin"
        val attachmentType = data["attachment_type"]
        val attachmentSize = data["attachment_size"]?.toLongOrNull()
        val attachmentExpires = data["attachment_expires"]?.toLongOrNull()
        val attachmentUrl = data["attachment_url"]
        val truncated = (data["truncated"] ?: "") == "1"
        if (id == null || topic == null || message == null || timestamp == null) {
            Log.d(TAG, "Discarding unexpected message: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}, data=${data}")
            return
        }
        Log.d(TAG, "Received message: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}, data=${data}")

        CoroutineScope(job).launch {
            val baseUrl = getString(R.string.app_base_url) // Everything from Firebase comes from main service URL!

            // Check if notification was truncated and discard if it will (or likely already did) arrive via instant delivery
            val subscription = repository.getSubscription(baseUrl, topic) ?: return@launch
            if (truncated && subscription.instant) {
                Log.d(TAG, "Discarding truncated message that did/will arrive via instant delivery: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}, data=${data}")
                return@launch
            }

            // Add notification
            val decodedMessage = if (encoding == MESSAGE_ENCODING_BASE64) {
                String(Base64.decode(message, Base64.DEFAULT))
            } else {
                message
            }
            val attachment = if (attachmentUrl != null) {
                Attachment(
                    name = attachmentName,
                    type = attachmentType,
                    size = attachmentSize,
                    expires = attachmentExpires,
                    url = attachmentUrl,
                )
            } else null
            val notification = Notification(
                id = id,
                subscriptionId = subscription.id,
                timestamp = timestamp,
                title = title ?: "",
                message = decodedMessage,
                priority = toPriority(priority),
                tags = tags ?: "",
                click = click ?: "",
                attachment = attachment,
                notificationId = Random.nextInt(),
                deleted = false
            )
            if (repository.addNotification(notification)) {
                Log.d(TAG, "Dispatching notification for message: from=${remoteMessage.from}, fcmprio=${remoteMessage.priority}, fcmprio_orig=${remoteMessage.originalPriority}, data=${data}")
                dispatcher.dispatch(subscription, notification)
            }
        }
    }

    override fun onNewToken(token: String) {
        // Called if the FCM registration token is updated
        // We don't actually use or care about the token, since we're using topics
        Log.d(TAG, "Registration token was updated: $token")
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    companion object {
        private const val TAG = "NtfyFirebase"
    }
}
