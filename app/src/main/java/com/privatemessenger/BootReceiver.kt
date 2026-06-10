package com.privatemessenger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Prefs.isSetupDone(context)) {
                val serviceIntent = Intent(context, MqttService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
