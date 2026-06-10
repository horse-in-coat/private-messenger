package com.privatemessenger

data class Message(
    val id: String,
    val text: String?,
    val imageBase64: String?,   // null if text-only
    val timestamp: Long,
    val isOutgoing: Boolean,
    var status: Status = Status.SENDING
) {
    enum class Status { SENDING, SENT, DELIVERED }

    val isImage: Boolean get() = imageBase64 != null
}

data class MqttPayload(
    val id: String,
    val text: String?,
    val imageBase64: String?,
    val timestamp: Long,
    val type: String   // "msg" | "ack"
)
