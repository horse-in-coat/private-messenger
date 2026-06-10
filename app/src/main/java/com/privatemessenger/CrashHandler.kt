package com.privatemessenger

import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val stackTrace = throwable.stackTraceToString()
            val prefs = context.getSharedPreferences("crash_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_crash", stackTrace).apply()

            val intent = Intent(context, CrashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("crash_log", stackTrace)
            }
            context.startActivity(intent)
            exitProcess(1)
        } catch (e: Exception) {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
