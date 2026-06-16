package com.privatemessenger

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object Prefs {
    private const val NAME = "messenger_prefs"
    private const val KEY_SECRET = "secret_code"
    private const val KEY_MY_NAME = "my_name"
    private const val KEY_DARK_THEME = "dark_theme"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_THEME_SET = "theme_set"
    private const val KEY_DEVICE_ID = "device_id"

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

    // Уникальный ID этого устройства — генерируется один раз при первой установке
    fun getDeviceId(ctx: Context): String {
        var id = prefs(ctx).getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").take(8)
            prefs(ctx).edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getTopic(ctx: Context): String {
        val secret = getSecret(ctx)
        val hash = secret.hashCode().toString(16).takeLast(8)
        return "pm_${hash}"
    }

    // Топик для отправки — содержит уникальный ID этого устройства
    fun getMyTopic(ctx: Context): String = "${getTopic(ctx)}/${getDeviceId(ctx)}"

    // Топик для подписки — wildcard на все устройства в группе
    fun getGroupWildcardTopic(ctx: Context): String = "${getTopic(ctx)}/+"

    // Топик для ACK конкретного устройства
    fun getAckTopic(ctx: Context, deviceId: String): String = "${getTopic(ctx)}/ack/${deviceId}"

    fun getMyAckTopic(ctx: Context): String = getAckTopic(ctx, getDeviceId(ctx))

    fun getAckWildcardTopic(ctx: Context): String = "${getTopic(ctx)}/ack/+"
}
