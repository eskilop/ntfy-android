package io.heckel.ntfy.msg

import android.util.Base64
import com.google.gson.Gson
import io.heckel.ntfy.db.Attachment
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.util.joinTags
import io.heckel.ntfy.util.toPriority

class NotificationParser {
    private val gson = Gson()

    fun parse(s: String, subscriptionId: Long = 0, notificationId: Int = 0): Notification? {
        val notificationWithTopic = parseWithTopic(s, subscriptionId = subscriptionId, notificationId = notificationId)
        return notificationWithTopic?.notification
    }

    fun parseWithTopic(s: String, subscriptionId: Long = 0, notificationId: Int = 0): NotificationWithTopic? {
        val message = gson.fromJson(s, Message::class.java)
        if (message.event != ApiService.EVENT_MESSAGE) {
            return null
        }
        val decodedMessage = if (message.encoding == MESSAGE_ENCODING_BASE64) {
            String(Base64.decode(message.message, Base64.DEFAULT))
        } else {
            message.message
        }
        val attachment = if (message.attachment?.url != null) {
            Attachment(
                name = message.attachment.name,
                type = message.attachment.type,
                size = message.attachment.size,
                expires = message.attachment.expires,
                url = message.attachment.url,
            )
        } else null
        val notification = Notification(
            id = message.id,
            subscriptionId = subscriptionId,
            timestamp = message.time,
            title = message.title ?: "",
            message = decodedMessage,
            priority = toPriority(message.priority),
            tags = joinTags(message.tags),
            click = message.click ?: "",
            attachment = attachment,
            notificationId = notificationId,
            deleted = false
        )
        return NotificationWithTopic(message.topic, notification)
    }

    data class NotificationWithTopic(val topic: String, val notification: Notification)
}
