package coredevices.coreapp.push

interface PushNotificationListener {
    fun onNewToken(token: String)

    fun onPushNotification(title: String?, body: String?)

    fun onPushNotificationWithPayloadData(
        title: String?,
        body: String?,
        data: Map<Any?, *>,
    )
}

expect object PlatformPushNotifications {
    fun initialize()

    fun addListener(listener: PushNotificationListener)

    fun notifyLocal(id: Int, title: String, body: String)

    fun removeLocal(id: Int)

    fun handleRemoteNotification(userInfo: Map<Any?, *>)
}
