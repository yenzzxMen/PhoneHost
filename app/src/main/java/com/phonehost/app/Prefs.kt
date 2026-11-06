package com.phonehost.app

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("phonehost", Context.MODE_PRIVATE)

    fun port(c: Context): Int = sp(c).getInt("port", 8080)
    fun setPort(c: Context, v: Int) = sp(c).edit().putInt("port", v).apply()

    fun autoStart(c: Context): Boolean = sp(c).getBoolean("autostart", false)
    fun setAutoStart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autostart", v).apply()

    fun token(c: Context): String = sp(c).getString("cf_token", "") ?: ""
    fun setToken(c: Context, v: String) = sp(c).edit().putString("cf_token", v).apply()

    fun termuxCmd(c: Context): String = sp(c).getString("termux_cmd", "python -m http.server 5000") ?: ""
    fun setTermuxCmd(c: Context, v: String) = sp(c).edit().putString("termux_cmd", v).apply()
}
