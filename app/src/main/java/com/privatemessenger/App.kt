package com.privatemessenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // IMPORTANCE_NONE — уведомление полностью скрыто из шторки
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Фоновое соединение",
            NotificationManager.IMPORTANCE_NONE
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        val msgChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Новые сообщения",
            NotificationManager.IMPORTANCE_HIGH
        )

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(msgChannel)
    }

    companion object {
        const val CHANNEL_SERVICE = "channel_service"
        const val CHANNEL_MESSAGES = "channel_messages"
    }
}
