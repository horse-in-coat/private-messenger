package com.privatemessenger

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "messenger_prefs"
    private const val KEY_SECRET = "secret_code"
    private const val KEY_MY_NAME = "my_name"
    private const val KEY_DARK_THEME = "dark_theme"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_THEME_SET = "theme_set"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getSecret(ctx: Context): String = prefs(ctx).getString(KEY_SECRET, "") ?: ""
    fun setSecret(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_SECRET, v).apply()

    fun getMyName(ctx: Context): String = prefs(ctx).getString(KEY_MY_NAME, "Я") ?: "Я"
    fun setMyName(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_MY_NAME, v).apply()

    fun isDarkTheme(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DARK_THEME, false)
    fun setDarkTheme(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_DARK_THEME, v).apply()

    fun isThemeSet(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_THEME_SET, false)
    fun setDarkThemeWithFlag(ctx: Context, v: Boolean) {
        prefs(ctx).edit()
            .putBoolean(KEY_DARK_THEME, v)
            .putBoolean(KEY_THEME_SET, true)
            .apply()
    }

    fun isSetupDone(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SETUP_DONE, false)
    fun setSetupDone(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_SETUP_DONE, v).apply()

    fun getTopic(ctx: Context): String {
        val secret = getSecret(ctx)
        val hash = secret.hashCode().toString(16).takeLast(8)
        return "pm_${hash}"
    }

    fun getMyTopic(ctx: Context): String = "${getTopic(ctx)}/a"
    fun getPeerTopic(ctx: Context): String = "${getTopic(ctx)}/b"
}
