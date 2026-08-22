package com.miku.systemui

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MikuNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        Log.d("MikuOS_SystemUI", "Notification posted: ${sbn?.packageName}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Log.d("MikuOS_SystemUI", "Notification removed: ${sbn?.packageName}")
    }
}
