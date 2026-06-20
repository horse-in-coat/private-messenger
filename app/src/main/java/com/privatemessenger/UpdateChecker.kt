package com.privatemessenger

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO = "horse-in-coat/private-messenger"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    // Текущая версия приложения — обновляй вручную при каждом релизе
    const val CURRENT_VERSION = "v1.1"

    data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)

    fun checkForUpdate(callback: (UpdateInfo?) -> Unit) {
        Thread {
            try {
                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "GitHub API responded with $responseCode")
                    callback(null)
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val latestVersion = json.getString("tag_name")
                val notes = json.optString("body", "")

                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                if (apkUrl == null) {
                    callback(null)
                    return@Thread
                }

                if (isNewerVersion(latestVersion, CURRENT_VERSION)) {
                    callback(UpdateInfo(latestVersion, apkUrl, notes))
                } else {
                    callback(null)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Update check failed: ${e.message}")
                callback(null)
            }
        }.start()
    }

    // Простое сравнение версий вида v1.2 > v1.1
    private fun isNewerVersion(remote: String, current: String): Boolean {
        try {
            val r = remote.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val c = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(r.size, c.size)
            for (i in 0 until maxLen) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    fun downloadAndInstall(ctx: Context, apkUrl: String, version: String) {
        val fileName = "messenger_$version.apk"
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Обновление мессенджера")
            .setDescription("Загрузка версии $version")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")

        val manager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
    }
}
