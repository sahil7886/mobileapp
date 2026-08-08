package coredevices.coreapp.push

import CoreAppVersion
import PlatformContext
import co.touchlab.kermit.Logger
import coredevices.coreapp.api.BugReports
import kotlin.time.Instant

class PushMessaging(
    private val bugReports: BugReports,
) {
    private val logger = Logger.withTag("PushMessaging")

    fun init() {
        PlatformPushNotifications.addListener(object : PushNotificationListener {
            override fun onNewToken(token: String) {
                // The token remains on-device until a direct APNs provider is
                // configured. Do not upload it from the app.
                logger.v { "Received an APNs token" }
            }

            override fun onPushNotification(title: String?, body: String?) {
                logger.v { "onPushNotification: title=$title body=$body" }
            }

            override fun onPushNotificationWithPayloadData(
                title: String?,
                body: String?,
                data: Map<Any?, *>,
            ) {
                handleMessage(data)
            }
        })

    }

    private fun handleMessage(data: Map<Any?, *>) {
        val type = data["type"]
        logger.d { "handleMessage: type=$type data=$data" }
        when (type) {
            "atlas_message" -> {
                val title = data["title"] as? String?
                val body = data["body"] as? String?
                val conversationId = data["conversationId"] as? String?
                val timestamp = data["timestamp"] as? String?
                if (title == null) {
                    logger.e { "title is null for atlas_message" }
                    return
                }
                if (body == null) {
                    logger.e { "body is null for atlas_message" }
                    return
                }
                if (conversationId == null) {
                    logger.e { "conversationId is null for atlas_message" }
                    return
                }
                if (timestamp == null) {
                    logger.e { "timestamp is null for atlas_message" }
                    return
                }
                val ts = try {
                    Instant.parse(timestamp)
                } catch (e: IllegalArgumentException) {
                    logger.e(e) { "Failed to parse timestamp: $timestamp" }
                    null
                }
                if (ts == null) {
                    return
                }
                val message = AtlasPushMessage(
                    conversationId = conversationId,
                    title = title,
                    body = body,
                    timestamp = ts,
                )
                bugReports.handlePushMessage(message)
            }
        }
    }

}

data class AtlasPushMessage(
    val conversationId: String,
    val title: String,
    val body: String,
    val timestamp: Instant,
)

expect fun PlatformContext.getDeviceId(): String
