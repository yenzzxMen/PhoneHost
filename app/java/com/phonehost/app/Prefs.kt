package com.phonehost.app

import android.content.Context

object Prefs {
    const val PHP_PORT = 8081

    private fun sp(c: Context) = c.getSharedPreferences("phonehost", Context.MODE_PRIVATE)

    fun port(c: Context): Int = sp(c).getInt("port", 8080)
    fun setPort(c: Context, v: Int) = sp(c).edit().putInt("port", v).apply()

    fun autoStart(c: Context): Boolean = sp(c).getBoolean("autostart", false)
    fun setAutoStart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autostart", v).apply()

    fun token(c: Context): String = sp(c).getString("cf_token", "") ?: ""
    fun setToken(c: Context, v: String) = sp(c).edit().putString("cf_token", v).apply()

    fun termuxCmd(c: Context): String = sp(c).getString("termux_cmd", "python -m http.server 5000") ?: ""
    fun setTermuxCmd(c: Context, v: String) = sp(c).edit().putString("termux_cmd", v).apply()

    fun phpMode(c: Context): Boolean = sp(c).getBoolean("php_mode", false)
    fun setPhpMode(c: Context, v: Boolean) = sp(c).edit().putBoolean("php_mode", v).apply()

    fun publicUrl(c: Context): String = sp(c).getString("public_url", "") ?: ""
    fun setPublicUrl(c: Context, v: String) = sp(c).edit().putString("public_url", v).apply()

    fun syncToken(c: Context): String {
        val s = sp(c)
        var t = s.getString("sync_token", null)
        if (t == null) {
            t = java.util.UUID.randomUUID().toString().replace("-", "")
            s.edit().putString("sync_token", t).apply()
        }
        return t ?: ""
    }
}
