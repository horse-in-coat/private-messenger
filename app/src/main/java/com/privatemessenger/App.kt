package com.privatemessenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // Foreground service channel
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Фоновое соединение",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Поддерживает соединение с сервером"
        }

        // Messages channel
        val msgChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Новые сообщения",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Уведомления о входящих сообщениях"
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(msgChannel)
    }

    companion object {
        const val CHANNEL_SERVICE = "channel_service"
        const val CHANNEL_MESSAGES = "channel_messages"
    }
}
