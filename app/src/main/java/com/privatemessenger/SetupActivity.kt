package com.privatemessenger

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.textfield.TextInputEditText

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        if (Prefs.isSetupDone(this)) {
            startChat()
            return
        }

        setContentView(R.layout.activity_setup)

        val etName = findViewById<TextInputEditText>(R.id.etName)
        val etSecret = findViewById<TextInputEditText>(R.id.etSecret)
        val btnStart = findViewById<Button>(R.id.btnStart)

        btnStart.setOnClickListener {
            val secret = etSecret.text.toString().trim()
            val name = etName.text.toString().trim()

            if (secret.length < 4) {
                Toast.makeText(this, "Код должен быть не менее 4 символов", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (name.isEmpty()) {
                Toast.makeText(this, "Введите своё имя", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Prefs.setSecret(this, secret)
            Prefs.setMyName(this, name)
            Prefs.setSetupDone(this, true)
            startChat()
        }
    }

    private fun applyTheme() {
        if (!Prefs.isThemeSet(this)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else if (Prefs.isDarkTheme(this)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private fun startChat() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }
}
