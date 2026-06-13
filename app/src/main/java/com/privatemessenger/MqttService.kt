package com.privatemessenger

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.RingtoneManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import java.text.SimpleDateFormat
import java.util.*

class MqttService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): MqttService = this@MqttService
    }

    private val binder = LocalBinder()
    private var mqttClient: Mqtt3AsyncClient? = null
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0

    var onMessageReceived: ((Message) -> Unit)? = null
    var onAckReceived: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    companion object {
        const val TAG = "MqttService"
        const val BROKER_HOST = "mqtt.flespi.io"
        const val BROKER_PORT = 8883
        const val BROKER_PASS = "hE485yRDByd4X5SFhw3ZpjT9kfp6pPhCX4eO7CTYdQyKMHDyTkbvOZuebUh19STo"
        const val NOTIFICATION_ID = 1
        var isRunning = false

        val connectionLog = mutableListOf<String>()

        fun addLog(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "[$time] $msg"
            Log.i(TAG, line)
            connectionLog.add(line)
            if (connectionLog.size > 100) connectionLog.removeAt(0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        addLog("Сервис запущен")
        startForeground(NOTIFICATION_ID, buildSilentNotification())
        connect()
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        addLog("Сервис остановлен")
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
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun connect() {
        val clientId = "pm_" + Prefs.getMyTopic(this).replace("/", "_")
        addLog("Подключение к $BROKER_HOST:$BROKER_PORT (попытка ${reconnectAttempt + 1})")
        addLog("ClientID: $clientId")
        addLog("Топик входящих: ${Prefs.getPeerTopic(this)}")
        addLog("Топик исходящих: ${Prefs.getMyTopic(this)}")

        try {
            mqttClient?.disconnect()
            mqttClient = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(BROKER_HOST)
                .serverPort(BROKER_PORT)
                .sslWithDefaultConfig()
                .simpleAuth()
                    .username(BROKER_PASS)
                    .password("".toByteArray())
                    .applySimpleAuth()
                .buildAsync()

            addLog("MQTT клиент создан, отправляю CONNECT...")

            mqttClient?.connectWith()
                ?.cleanSession(false)
                ?.keepAlive(60)
                ?.send()
                ?.whenComplete { ack, throwable ->
                    if (throwable == null) {
                        reconnectAttempt = 0
                        addLog("✓ Подключено успешно!")
                        addLog("Session present: ${ack.isSessionPresent}")
                        onConnectionChanged?.invoke(true)
                        subscribeToTopics()
                    } else {
                        addLog("✗ Ошибка: ${throwable.javaClass.simpleName}")
                        addLog("  Причина: ${throwable.message}")
                        throwable.cause?.let { addLog("  Причина2: ${it.message}") }
                        onConnectionChanged?.invoke(false)
                        scheduleReconnect()
                    }
                }
        } catch (e: Exception) {
            addLog("✗ Исключение: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempt++
        val delayMs = minOf(5000L * (1 shl minOf(reconnectAttempt - 1, 3)), 60000L)
        addLog("Переподключение через ${delayMs / 1000}с...")
        handler.postDelayed({ connect() }, delayMs)
    }

    private fun subscribeToTopics() {
        val peerTopic = Prefs.getPeerTopic(this)
        val ackTopic = "${Prefs.getMyTopic(this)}/ack"

        addLog("Подписываюсь на: $peerTopic")
        mqttClient?.subscribeWith()
            ?.topicFilter(peerTopic)
            ?.callback { publish -> handleIncoming(publish, false) }
            ?.send()
            ?.whenComplete { _, t ->
                if (t == null) addLog("✓ Подписка на входящие OK")
                else addLog("✗ Ошибка подписки: ${t.message}")
            }

        addLog("Подписываюсь на: $ackTopic")
        mqttClient?.subscribeWith()
            ?.topicFilter(ackTopic)
            ?.callback { publish -> handleIncoming(publish, true) }
            ?.send()
            ?.whenComplete { _, t ->
                if (t == null) addLog("✓ Подписка на ACK OK")
                else addLog("✗ Ошибка подписки ACK: ${t.message}")
            }
    }

    private fun handleIncoming(publish: Mqtt3Publish, isAck: Boolean) {
        try {
            val json = String(publish.payloadAsBytes)
            val payload = gson.fromJson(json, MqttPayload::class.java)

            if (isAck) {
                addLog("← ACK получен: ${payload.id.take(8)}...")
                onAckReceived?.invoke(payload.id)
                return
            }

            addLog("← Сообщение получено от собеседника")
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
            addLog("✗ Ошибка разбора сообщения: ${e.message}")
        }
    }

    private fun sendAck(messageId: String) {
        val ackTopic = "${Prefs.getPeerTopic(this)}/ack"
        val payload = MqttPayload(messageId, null, null, System.currentTimeMillis(), "ack")
        try {
            mqttClient?.publishWith()?.topic(ackTopic)
                ?.payload(gson.toJson(payload).toByteArray())?.send()
        } catch (e: Exception) {
            addLog("✗ Ошибка отправки ACK: ${e.message}")
        }
    }

    fun sendMessage(text: String?, imageBase64: String?, callback: (Boolean, String) -> Unit) {
        val id = UUID.randomUUID().toString()
        val payload = MqttPayload(id, text, imageBase64, System.currentTimeMillis(), "msg")
        val topic = Prefs.getMyTopic(this)
        addLog("→ Отправка сообщения в $topic")
        try {
            mqttClient?.publishWith()?.topic(topic)
                ?.payload(gson.toJson(payload).toByteArray())
                ?.retain(false)
                ?.send()
                ?.whenComplete { _, throwable ->
                    if (throwable == null) addLog("✓ Сообщение отправлено")
                    else addLog("✗ Ошибка отправки: ${throwable.message}")
                    callback(throwable == null, id)
                }
        } catch (e: Exception) {
            addLog("✗ Исключение при отправке: ${e.message}")
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
            .setSmallIcon(android.R.drawable.ic_dialog_email)
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
