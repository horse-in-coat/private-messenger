package com.privatemessenger

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

class ChatActivity : AppCompatActivity() {

    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnAttach: Button
    private lateinit var tvStatus: TextView

    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<Message>()

    private var mqttService: MqttService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MqttService.LocalBinder
            mqttService = binder.getService()
            bound = true
            setupServiceCallbacks()
            updateConnectionStatus()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            mqttService = null
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> sendImage(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        tvStatus = findViewById(R.id.tvStatus)

        setupRecycler()

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendTextMessage(text)
                etMessage.text.clear()
            }
        }

        btnAttach.setOnClickListener { openGallery() }

        requestNotificationPermission()
        startAndBindService()
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

    private fun setupRecycler() {
        adapter = MessageAdapter(messages)
        val lm = LinearLayoutManager(this)
        lm.stackFromEnd = true
        rvMessages.layoutManager = lm
        rvMessages.adapter = adapter
    }

    private fun setupServiceCallbacks() {
        mqttService?.onMessageReceived = { msg ->
            runOnUiThread {
                messages.add(msg)
                adapter.notifyItemInserted(messages.size - 1)
                rvMessages.scrollToPosition(messages.size - 1)
            }
        }
        mqttService?.onAckReceived = { msgId ->
            runOnUiThread {
                val idx = messages.indexOfFirst { it.id == msgId }
                if (idx >= 0) {
                    messages[idx] = messages[idx].copy(status = Message.Status.DELIVERED)
                    adapter.notifyItemChanged(idx)
                }
            }
        }
        mqttService?.onConnectionChanged = { connected ->
            runOnUiThread { updateConnectionStatus(connected) }
        }
    }

    private fun updateConnectionStatus(connected: Boolean? = null) {
        val isConnected = connected ?: (mqttService?.isConnected() == true)
        tvStatus.text = if (isConnected) "● Подключено" else "○ Нет соединения"
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (isConnected) R.color.status_connected else R.color.status_disconnected
            )
        )
    }

    private fun sendTextMessage(text: String) {
        val tempId = "temp_${System.currentTimeMillis()}"
        val tempMsg = Message(tempId, text, null, System.currentTimeMillis(), true, Message.Status.SENDING)
        messages.add(tempMsg)
        adapter.notifyItemInserted(messages.size - 1)
        rvMessages.scrollToPosition(messages.size - 1)

        mqttService?.sendMessage(text, null) { success, id ->
            runOnUiThread {
                val idx = messages.indexOfFirst { it.id == tempId }
                if (idx >= 0) {
                    messages[idx] = messages[idx].copy(
                        id = id,
                        status = if (success) Message.Status.SENT else Message.Status.SENDING
                    )
                    adapter.notifyItemChanged(idx)
                }
            }
        }
    }

    private fun sendImage(uri: Uri) {
        Toast.makeText(this, "Отправка фото...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val stream = contentResolver.openInputStream(uri)!!
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                val scaled = scaleBitmap(bmp, 800)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.DEFAULT)

                val tempId = "temp_${System.currentTimeMillis()}"
                val tempMsg = Message(tempId, null, base64, System.currentTimeMillis(), true, Message.Status.SENDING)
                runOnUiThread {
                    messages.add(tempMsg)
                    adapter.notifyItemInserted(messages.size - 1)
                    rvMessages.scrollToPosition(messages.size - 1)
                }
                mqttService?.sendMessage(null, base64) { success, id ->
                    runOnUiThread {
                        val idx = messages.indexOfFirst { it.id == tempId }
                        if (idx >= 0) {
                            messages[idx] = messages[idx].copy(
                                id = id,
                                status = if (success) Message.Status.SENT else Message.Status.SENDING
                            )
                            adapter.notifyItemChanged(idx)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка отправки фото", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun scaleBitmap(bmp: Bitmap, maxSize: Int): Bitmap {
        val w = bmp.width; val h = bmp.height
        if (w <= maxSize && h <= maxSize) return bmp
        val ratio = maxSize.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bmp, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    private fun openGallery() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
            return
        }
        pickImageLauncher.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openGallery()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, MqttService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bindService(Intent(this, MqttService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) { unbindService(connection); bound = false }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        val isDark = Prefs.isDarkTheme(this)
        menu.findItem(R.id.action_theme).title = if (isDark) "☀ Светлая тема" else "☾ Тёмная тема"
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_theme -> {
                val newDark = !Prefs.isDarkTheme(this)
                Prefs.setDarkThemeWithFlag(this, newDark)
                AppCompatDelegate.setDefaultNightMode(
                    if (newDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
                recreate()
                true
            }
            R.id.action_reset -> {
                AlertDialog.Builder(this)
                    .setTitle("Сброс настроек")
                    .setMessage("Вернуться к начальному экрану?")
                    .setPositiveButton("Да") { _, _ ->
                        Prefs.setSetupDone(this, false)
                        startActivity(Intent(this, SetupActivity::class.java))
                        finish()
                    }
                    .setNegativeButton("Отмена", null).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
