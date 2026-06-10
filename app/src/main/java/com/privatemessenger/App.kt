package com.privatemessenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Фоновое соединение", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Новые сообщения", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "channel_service"
        const val CHANNEL_MESSAGES = "channel_messages"
    }
}
