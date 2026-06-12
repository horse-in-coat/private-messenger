package com.privatemessenger

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshLog()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val btnCopy = Button(this).apply {
            text = "📋 Скопировать лог"
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("log",
                    MqttService.connectionLog.joinToString("\n")))
                Toast.makeText(this@LogActivity, "Скопировано!", Toast.LENGTH_SHORT).show()
            }
        }

        val btnClear = Button(this).apply {
            text = "🗑 Очистить"
            setOnClickListener {
                MqttService.connectionLog.clear()
                refreshLog()
            }
        }

        scrollView = ScrollView(this)
        tvLog = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(8, 8, 8, 8)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(tvLog)

        layout.addView(btnCopy)
        layout.addView(btnClear)
        layout.addView(scrollView)
        setContentView(layout)

        supportActionBar?.title = "Лог подключения"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refreshLog() {
        val text = if (MqttService.connectionLog.isEmpty())
            "Лог пуст. Перезапусти приложение."
        else
            MqttService.connectionLog.joinToString("\n")
        tvLog.text = text
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
