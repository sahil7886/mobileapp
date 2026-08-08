package coredevices.coreapp.push

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

actual object PlatformPushNotifications {
    private val listeners = mutableListOf<PushNotificationListener>()

    actual fun initialize() = Unit

    actual fun addListener(listener: PushNotificationListener) {
        listeners += listener
    }

    actual fun notifyLocal(id: Int, title: String, body: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = 0.1,
            repeats = false,
        )
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = id.toString(),
            content = content,
            trigger = trigger,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    actual fun removeLocal(id: Int) {
        val identifiers = listOf(id.toString())
        UNUserNotificationCenter.currentNotificationCenter().removePendingNotificationRequestsWithIdentifiers(identifiers)
        UNUserNotificationCenter.currentNotificationCenter().removeDeliveredNotificationsWithIdentifiers(identifiers)
    }

    actual fun handleRemoteNotification(userInfo: Map<Any?, *>) {
        val aps = userInfo["aps"] as? Map<*, *>
        val alert = aps?.get("alert") as? Map<*, *>
        val title = alert?.get("title") as? String
        val body = alert?.get("body") as? String
        listeners.toList().forEach { listener ->
            listener.onPushNotificationWithPayloadData(title, body, userInfo)
        }
    }
}
