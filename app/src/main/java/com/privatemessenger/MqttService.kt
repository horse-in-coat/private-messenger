package com.privatemessenger

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.RingtoneManager
import android.os.Binder
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

class MqttService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): MqttService = this@MqttService
    }

    private val binder = LocalBinder()
    private var mqttClient: MqttClient? = null
    private val gson = Gson()

    var onMessageReceived: ((Message) -> Unit)? = null
    var onAckReceived: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    companion object {
        const val TAG = "MqttService"
        const val BROKER_URL = "ssl://broker.hivemq.com:8883"
        const val NOTIFICATION_ID = 1
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        connect()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        disconnect()
        super.onDestroy()
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setContentTitle("Мессенджер активен")
            .setContentText("Ожидание сообщений...")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun connect() {
        val clientId = "pm_" + UUID.randomUUID().toString().take(8)
        try {
            mqttClient = MqttClient(BROKER_URL, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 30
                keepAliveInterval = 60
                isAutomaticReconnect = true
                socketFactory = javax.net.ssl.SSLSocketFactory.getDefault()
            }
            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost: ${cause?.message}")
                    onConnectionChanged?.invoke(false)
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    handleIncoming(topic, message)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient?.connect(options)
            subscribeToTopics()
            onConnectionChanged?.invoke(true)
            Log.i(TAG, "Connected to broker")

        } catch (e: Exception) {
            Log.e(TAG, "Connect error: ${e.message}")
            onConnectionChanged?.invoke(false)
        }
    }

    private fun subscribeToTopics() {
        val peerTopic = Prefs.getPeerTopic(this)
        val ackTopic = "${Prefs.getMyTopic(this)}/ack"
        mqttClient?.subscribe(arrayOf(peerTopic, ackTopic), intArrayOf(1, 1))
        Log.i(TAG, "Subscribed to: $peerTopic, $ackTopic")
    }

    private fun handleIncoming(topic: String, mqttMessage: MqttMessage) {
        try {
            val json = String(mqttMessage.payload)
            val payload = gson.fromJson(json, MqttPayload::class.java)

            if (topic.endsWith("/ack")) {
                onAckReceived?.invoke(payload.id)
                return
            }

            // Send ACK back
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
        val payload = MqttPayload(
            id = messageId,
            text = null,
            imageBase64 = null,
            timestamp = System.currentTimeMillis(),
            type = "ack"
        )
        try {
            val msg = MqttMessage(gson.toJson(payload).toByteArray())
            msg.qos = 1
            mqttClient?.publish(ackTopic, msg)
        } catch (e: Exception) {
            Log.e(TAG, "ACK send error: ${e.message}")
        }
    }

    fun sendMessage(text: String?, imageBase64: String?, callback: (Boolean, String) -> Unit): String {
        val id = UUID.randomUUID().toString()
        val payload = MqttPayload(
            id = id,
            text = text,
            imageBase64 = imageBase64,
            timestamp = System.currentTimeMillis(),
            type = "msg"
        )
        val topic = Prefs.getMyTopic(this)
        try {
            val json = gson.toJson(payload)
            val msg = MqttMessage(json.toByteArray()).apply { qos = 1 }
            mqttClient?.publish(topic, msg)
            callback(true, id)
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
            callback(false, id)
        }
        return id
    }

    fun isConnected(): Boolean = mqttClient?.isConnected == true

    private fun showMessageNotification(msg: Message) {
        val text = when {
            msg.isImage -> "📷 Фото"
            else -> msg.text ?: ""
        }
        val intent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(this, App.CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Новое сообщение")
            .setContentText(text)
            .setAutoCancel(true)
            .setSound(sound)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(2, notification)
    }

    private fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
    }
}
