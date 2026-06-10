package com.privatemessenger

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.privatemessenger.databinding.ActivitySetupBinding

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        // If already set up, go straight to chat
        if (Prefs.isSetupDone(this)) {
            startChat()
            return
        }

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            val secret = binding.etSecret.text.toString().trim()
            val name = binding.etName.text.toString().trim()

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
        if (Prefs.isDarkTheme(this)) {
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
