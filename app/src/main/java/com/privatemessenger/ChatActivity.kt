package com.privatemessenger

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.privatemessenger.databinding.ActivityChatBinding
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
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
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecycler()
        setupButtons()
        requestPermissions()
        startAndBindService()
    }

    private fun applyTheme() {
        if (Prefs.isDarkTheme(this)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private fun setupRecycler() {
        adapter = MessageAdapter(messages)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendTextMessage(text)
                binding.etMessage.text?.clear()
            }
        }

        binding.btnAttach.setOnClickListener {
            openGallery()
        }
    }

    private fun setupServiceCallbacks() {
        mqttService?.onMessageReceived = { msg ->
            runOnUiThread {
                messages.add(msg)
                adapter.notifyItemInserted(messages.size - 1)
                binding.rvMessages.scrollToPosition(messages.size - 1)
            }
        }

        mqttService?.onAckReceived = { msgId ->
            runOnUiThread {
                val idx = messages.indexOfFirst { it.id == msgId }
                if (idx >= 0) {
                    val updated = messages[idx].copy(status = Message.Status.DELIVERED)
                    messages[idx] = updated
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
        binding.tvStatus.text = if (isConnected) "● Подключено" else "○ Нет соединения"
        binding.tvStatus.setTextColor(
            if (isConnected)
                ContextCompat.getColor(this, R.color.status_connected)
            else
                ContextCompat.getColor(this, R.color.status_disconnected)
        )
    }

    private fun sendTextMessage(text: String) {
        val tempMsg = Message(
            id = "temp_${System.currentTimeMillis()}",
            text = text,
            imageBase64 = null,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = Message.Status.SENDING
        )
        messages.add(tempMsg)
        adapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        mqttService?.sendMessage(text, null) { success, id ->
            runOnUiThread {
                val idx = messages.indexOfFirst { it.id == tempMsg.id }
                if (idx >= 0) {
                    val updated = messages[idx].copy(
                        id = id,
                        status = if (success) Message.Status.SENT else Message.Status.SENDING
                    )
                    messages[idx] = updated
                    adapter.notifyItemChanged(idx)
                }
            }
        }
    }

    private fun sendImage(uri: Uri) {
        binding.btnSend.isEnabled = false
        Toast.makeText(this, "Отправка фото...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val base64 = uriToBase64(uri)
                val tempMsg = Message(
                    id = "temp_${System.currentTimeMillis()}",
                    text = null,
                    imageBase64 = base64,
                    timestamp = System.currentTimeMillis(),
                    isOutgoing = true,
                    status = Message.Status.SENDING
                )
                runOnUiThread {
                    messages.add(tempMsg)
                    adapter.notifyItemInserted(messages.size - 1)
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                    binding.btnSend.isEnabled = true
                }

                mqttService?.sendMessage(null, base64) { success, id ->
                    runOnUiThread {
                        val idx = messages.indexOfFirst { it.id == tempMsg.id }
                        if (idx >= 0) {
                            val updated = messages[idx].copy(
                                id = id,
                                status = if (success) Message.Status.SENT else Message.Status.SENDING
                            )
                            messages[idx] = updated
                            adapter.notifyItemChanged(idx)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnSend.isEnabled = true
                    Toast.makeText(this, "Ошибка отправки фото", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun uriToBase64(uri: Uri): String {
        val inputStream: InputStream = contentResolver.openInputStream(uri)!!
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        // Resize to max 800px to keep MQTT payload reasonable
        val scaled = scaleBitmap(bitmap, 800)

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.DEFAULT)
    }

    private fun scaleBitmap(bmp: Bitmap, maxSize: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxSize && h <= maxSize) return bmp
        val ratio = maxSize.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bmp, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    private fun openGallery() {
        if (!checkStoragePermission()) {
            requestStoragePermission()
            return
        }
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 100)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100)
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openGallery()
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, MqttService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bindService(Intent(this, MqttService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        val item = menu.findItem(R.id.action_theme)
        item.title = if (Prefs.isDarkTheme(this)) "☀ Светлая тема" else "☾ Тёмная тема"
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_theme -> {
                val newDark = !Prefs.isDarkTheme(this)
                Prefs.setDarkTheme(this, newDark)
                if (newDark) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                recreate()
                true
            }
            R.id.action_reset -> {
                AlertDialog.Builder(this)
                    .setTitle("Сброс настроек")
                    .setMessage("Удалить все настройки и вернуться к начальному экрану?")
                    .setPositiveButton("Да") { _, _ ->
                        Prefs.setSetupDone(this, false)
                        startActivity(Intent(this, SetupActivity::class.java))
                        finish()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
