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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
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
    private var isReconnecting = false
    private var wakeLock: PowerManager.WakeLock? = null

    var onMessageReceived: ((Message) -> Unit)? = null
    var onAckReceived: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    companion object {
        const val TAG = "MqttService"
        const val BROKER_HOST = "mqtt.flespi.io"
        const val BROKER_PORT = 8883
        const val BROKER_PASS = "hE485yRDByd4X5SFhw3ZpjT9kfp6pPhCX4eO7CTYdQyKMHDyTkbvOZuebUh19STo"
        const val NOTIFICATION_ID = 1
        // keepAlive 300с — телефон просыпается раз в 5 минут вместо раз в минуту
        const val KEEP_ALIVE_SECONDS = 300
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
        acquireWakeLock()
        connect()
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        addLog("Сервис остановлен")
        releaseWakeLock()
        try { mqttClient?.disconnect() } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PrivateMessenger::MqttWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {}
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
        if (isReconnecting) return
        isReconnecting = true

        val deviceId = Prefs.getDeviceId(this)
        val clientId = "pm_$deviceId"

        addLog("Подключение к $BROKER_HOST:$BROKER_PORT (попытка ${reconnectAttempt + 1})")
        addLog("ClientID: $clientId")
        addLog("Мой топик: ${Prefs.getMyTopic(this)}")

        try {
            try { mqttClient?.disconnect() } catch (e: Exception) {}

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
                .addDisconnectedListener { context ->
                    addLog("⚡ Соединение разорвано: ${context.cause.message}")
                    onConnectionChanged?.invoke(false)
                    if (isRunning) {
                        handler.postDelayed({
                            isReconnecting = false
                            scheduleReconnect()
                        }, 1000)
                    }
                }
                .buildAsync()

            addLog("MQTT клиент создан, отправляю CONNECT...")

            mqttClient?.connectWith()
                ?.cleanSession(false)
                ?.keepAlive(KEEP_ALIVE_SECONDS)
                ?.send()
                ?.whenComplete { ack, throwable ->
                    isReconnecting = false
                    if (throwable == null) {
                        reconnectAttempt = 0
                        addLog("✓ Подключено! keepAlive=${KEEP_ALIVE_SECONDS}с")
                        addLog("Session present: ${ack.isSessionPresent}")
                        onConnectionChanged?.invoke(true)
                        subscribeToTopics()
                    } else {
                        addLog("✗ Ошибка: ${throwable.javaClass.simpleName}: ${throwable.message}")
                        onConnectionChanged?.invoke(false)
                        scheduleReconnect()
                    }
                }
        } catch (e: Exception) {
            isReconnecting = false
            addLog("✗ Исключение: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempt++
        // Задержка: 15с, 30с, 60с, максимум 120с — менее агрессивно чем раньше
        val delayMs = minOf(15000L * (1 shl minOf(reconnectAttempt - 1, 3)), 120000L)
        addLog("Переподключение через ${delayMs / 1000}с...")
        handler.postDelayed({
            isReconnecting = false
            connect()
        }, delayMs)
    }

    private fun subscribeToTopics() {
        val groupTopic = Prefs.getGroupWildcardTopic(this)
        val ackTopic = Prefs.getAckWildcardTopic(this)

        mqttClient?.subscribeWith()
            ?.topicFilter(groupTopic)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.callback { publish -> handleIncoming(publish, false) }
            ?.send()
            ?.whenComplete { _, t ->
                if (t == null) addLog("✓ Подписка на группу OK")
                else addLog("✗ Ошибка подписки: ${t.message}")
            }

        mqttClient?.subscribeWith()
            ?.topicFilter(ackTopic)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.callback { publish -> handleIncoming(publish, true) }
            ?.send()
            ?.whenComplete { _, t ->
                if (t == null) addLog("✓ Подписка на ACK OK")
                else addLog("✗ Ошибка подписки ACK: ${t.message}")
            }
    }

    private fun handleIncoming(publish: Mqtt3Publish, isAckTopic: Boolean) {
        // Захватываем WakeLock на время обработки сообщения
        wakeLock?.acquire(10000L) // максимум 10 секунд

        try {
            val topic = publish.topic.toString()
            val json = String(publish.payloadAsBytes)
            val payload = gson.fromJson(json, MqttPayload::class.java)
            val myDeviceId = Prefs.getDeviceId(this)

            if (isAckTopic) {
                val targetId = topic.substringAfterLast("/")
                if (targetId != myDeviceId) return
                addLog("← ACK: ${payload.id.take(8)}...")
                onAckReceived?.invoke(payload.id)
                return
            }

            val senderId = topic.substringAfterLast("/")
            if (senderId == myDeviceId) return

            addLog("← Сообщение от: ${payload.senderName}")
            sendAck(payload.id, senderId)

            val msg = Message(
                id = payload.id,
                text = payload.text,
                imageBase64 = payload.imageBase64,
                timestamp = payload.timestamp,
                isOutgoing = false,
                senderName = payload.senderName,
                status = Message.Status.DELIVERED
            )
            onMessageReceived?.invoke(msg)
            showMessageNotification(msg)

        } catch (e: Exception) {
            addLog("✗ Ошибка разбора: ${e.message}")
        } finally {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun sendAck(messageId: String, toDeviceId: String) {
        val ackTopic = Prefs.getAckTopic(this, toDeviceId)
        val payload = MqttPayload(messageId, null, null, System.currentTimeMillis(), "ack")
        try {
            mqttClient?.publishWith()?.topic(ackTopic)
                ?.qos(MqttQos.AT_LEAST_ONCE)
                ?.payload(gson.toJson(payload).toByteArray())?.send()
        } catch (e: Exception) {
            addLog("✗ Ошибка ACK: ${e.message}")
        }
    }

    fun sendMessage(text: String?, imageBase64: String?, callback: (Boolean, String) -> Unit) {
        val id = UUID.randomUUID().toString()
        val payload = MqttPayload(
            id = id,
            text = text,
            imageBase64 = imageBase64,
            timestamp = System.currentTimeMillis(),
            type = "msg",
            senderName = Prefs.getMyName(this)
        )
        val topic = Prefs.getMyTopic(this)
        addLog("→ Отправка в $topic")
        try {
            mqttClient?.publishWith()?.topic(topic)
                ?.payload(gson.toJson(payload).toByteArray())
                ?.retain(false)
                ?.qos(MqttQos.AT_LEAST_ONCE)
                ?.send()
                ?.whenComplete { _, throwable ->
                    if (throwable == null) addLog("✓ Отправлено")
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
        val title = if (msg.senderName.isNotEmpty()) msg.senderName else "Новое сообщение"
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(this, App.CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setSound(sound)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, notification)
    }
}
