package com.privatemessenger

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val log = intent.getStringExtra("crash_log") ?: "Лог недоступен"

        val scrollView = ScrollView(this)
        val tv = TextView(this).apply {
            text = "❌ Ошибка при запуске:\n\n$log"
            textSize = 11f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }
        scrollView.addView(tv)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val btn = Button(this@CrashActivity).apply {
                text = "📋 Скопировать лог"
                setOnClickListener {
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("crash", log))
                    Toast.makeText(this@CrashActivity, "Скопировано!", Toast.LENGTH_SHORT).show()
                }
            }
            addView(btn)
            addView(scrollView)
        }
        setContentView(layout)
    }
}
