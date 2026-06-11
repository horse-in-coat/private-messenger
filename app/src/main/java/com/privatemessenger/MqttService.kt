package com.privatemessenger

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.RingtoneManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import java.util.UUID

class MqttService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): MqttService = this@MqttService
    }

    private val binder = LocalBinder()
    private var mqttClient: Mqtt3AsyncClient? = null
    private val gson = Gson()

    var onMessageReceived: ((Message) -> Unit)? = null
    var onAckReceived: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    companion object {
        const val TAG = "MqttService"
        const val BROKER_HOST = "mqtt.flespi.io"
        const val BROKER_PORT = 8883
        const val BROKER_USER = "FlespiToken"
        const val BROKER_PASS = "Jf1DpSw4Behcm7M6CJOTuPwVMho6ixnf3bLD7uEfZ9fw6dtX4KZQNmCNoTGaNQWJ"
        const val NOTIFICATION_ID = 1
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildSilentNotification())
        connect()
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        mqttClient?.disconnect()
        super.onDestroy()
    }

    private fun buildSilentNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, ChatActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setContentTitle("Мессенджер")
            .setContentText("")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun connect() {
        val clientId = "pm_" + Prefs.getMyTopic(this).replace("/", "_")

        mqttClient = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .serverHost(BROKER_HOST)
            .serverPort(BROKER_PORT)
            .sslWithDefaultConfig()
            .simpleAuth()
                .username(BROKER_USER)
                .password(BROKER_PASS.toByteArray())
                .applySimpleAuth()
            .buildAsync()

        mqttClient?.connectWith()
            ?.cleanSession(false)
            ?.keepAlive(60)
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable == null) {
                    Log.i(TAG, "Connected to flespi")
                    onConnectionChanged?.invoke(true)
                    subscribeToTopics()
                } else {
                    Log.e(TAG, "Connect error: ${throwable.message}")
                    onConnectionChanged?.invoke(false)
                }
            }
    }

    private fun subscribeToTopics() {
        val peerTopic = Prefs.getPeerTopic(this)
        val ackTopic = "${Prefs.getMyTopic(this)}/ack"

        mqttClient?.subscribeWith()
            ?.topicFilter(peerTopic)
            ?.callback { publish -> handleIncoming(publish, false) }
            ?.send()

        mqttClient?.subscribeWith()
            ?.topicFilter(ackTopic)
            ?.callback { publish -> handleIncoming(publish, true) }
            ?.send()
    }

    private fun handleIncoming(publish: Mqtt3Publish, isAck: Boolean) {
        try {
            val json = String(publish.payloadAsBytes)
            val payload = gson.fromJson(json, MqttPayload::class.java)

            if (isAck) {
                onAckReceived?.invoke(payload.id)
                return
            }

            sendAck(payload.id)

            val msg = Message(
                id = payload.id,
                text = payload.text,
                imageBase64 = payload.imageBase64,
                timestamp = payload.timestamp,
                isOutgoing = false,
                status = Message.Status.DELIVERED
            )
            onMessageReceived?.invoke(msg)
            showMessageNotification(msg)

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun sendAck(messageId: String) {
        val ackTopic = "${Prefs.getPeerTopic(this)}/ack"
        val payload = MqttPayload(messageId, null, null, System.currentTimeMillis(), "ack")
        try {
            mqttClient?.publishWith()?.topic(ackTopic)
                ?.payload(gson.toJson(payload).toByteArray())?.send()
        } catch (e: Exception) {
            Log.e(TAG, "ACK error: ${e.message}")
        }
    }

    fun sendMessage(text: String?, imageBase64: String?, callback: (Boolean, String) -> Unit) {
        val id = UUID.randomUUID().toString()
        val payload = MqttPayload(id, text, imageBase64, System.currentTimeMillis(), "msg")
        val topic = Prefs.getMyTopic(this)
        try {
            mqttClient?.publishWith()?.topic(topic)
                ?.payload(gson.toJson(payload).toByteArray())
                ?.retain(false)
                ?.send()
                ?.whenComplete { _, throwable -> callback(throwable == null, id) }
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
            callback(false, id)
        }
    }

    fun isConnected(): Boolean = mqttClient?.state?.isConnected == true

    private fun showMessageNotification(msg: Message) {
        val text = if (msg.isImage) "📷 Фото" else msg.text ?: ""
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(this, App.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Новое сообщение")
            .setContentText(text)
            .setAutoCancel(true)
            .setSound(sound)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, notification)
    }
}
