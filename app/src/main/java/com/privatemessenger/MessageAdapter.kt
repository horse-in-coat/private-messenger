package com.privatemessenger

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(private val messages: List<Message>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_OUTGOING = 1
        const val VIEW_TYPE_INCOMING = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isOutgoing) VIEW_TYPE_OUTGOING else VIEW_TYPE_INCOMING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_OUTGOING) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message_outgoing, parent, false)
            OutgoingViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message_incoming, parent, false)
            IncomingViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is OutgoingViewHolder) holder.bind(msg)
        else if (holder is IncomingViewHolder) holder.bind(msg)
    }

    override fun getItemCount() = messages.size

    inner class OutgoingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.tvText)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        private val ivImage: ImageView = view.findViewById(R.id.ivImage)

        fun bind(msg: Message) {
            if (msg.isImage) {
                tvText.visibility = View.GONE
                ivImage.visibility = View.VISIBLE
                val bmp = base64ToBitmap(msg.imageBase64!!)
                if (bmp != null) ivImage.setImageBitmap(bmp)
            } else {
                tvText.visibility = View.VISIBLE
                ivImage.visibility = View.GONE
                tvText.text = msg.text
            }
            tvTime.text = formatTime(msg.timestamp)
            tvStatus.text = when (msg.status) {
                Message.Status.SENDING -> "○"
                Message.Status.SENT -> "✓"
                Message.Status.DELIVERED -> "✓✓"
            }
        }
    }

    inner class IncomingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.tvText)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val ivImage: ImageView = view.findViewById(R.id.ivImage)

        fun bind(msg: Message) {
            if (msg.isImage) {
                tvText.visibility = View.GONE
                ivImage.visibility = View.VISIBLE
                val bmp = base64ToBitmap(msg.imageBase64!!)
                if (bmp != null) ivImage.setImageBitmap(bmp)
            } else {
                tvText.visibility = View.VISIBLE
                ivImage.visibility = View.GONE
                tvText.text = msg.text
            }
            tvTime.text = formatTime(msg.timestamp)
        }
    }

    private fun base64ToBitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun formatTime(ts: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(ts))
    }
}
