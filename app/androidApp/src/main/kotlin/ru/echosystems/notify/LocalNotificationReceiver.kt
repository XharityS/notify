package ru.echosystems.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LocalNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        showNotification(context, intent.getStringExtra("title") ?: "Notify",
            intent.getStringExtra("body") ?: "", intent.getStringExtra("style") ?: "basic")
    }
}
