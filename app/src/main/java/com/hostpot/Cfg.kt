package com.hostpot

import android.app.Application
import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong

class App : Application() {
    override fun onCreate() { super.onCreate(); Cfg.app = applicationContext }
}

object Stats {
    @Volatile var start = 0L
    val req = AtomicLong(); val bytes = AtomicLong()
    @Volatile var restarts = 0
    @Volatile var bUp = false
    val sites = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    fun hit(site: String) { sites.getOrPut(site) { AtomicLong() }.incrementAndGet() }
}

object Cfg {
    const val REPO = "OWNER/REPO"   // replaced by the GitHub workflow
    lateinit var app: Context
    private fun sp() = app.getSharedPreferences("ph", 0)
    var port: Int get() = sp().getInt("port", 8080); set(v) { sp().edit().putInt("port", v).apply() }
    var backendPort: Int get() = sp().getInt("bport", 8081); set(v) { sp().edit().putInt("bport", v).apply() }
    var token: String get() = sp().getString("token", "") ?: ""; set(v) { sp().edit().putString("token", v).apply() }
    var autoBoot: Boolean get() = sp().getBoolean("boot", true); set(v) { sp().edit().putBoolean("boot", v).apply() }
    var autoUpdate: Boolean get() = sp().getBoolean("upd", true); set(v) { sp().edit().putBoolean("upd", v).apply() }
    var wantRun: Boolean get() = sp().getBoolean("want", false); set(v) { sp().edit().putBoolean("want", v).apply() }
    var active: String get() = sp().getString("active", "default") ?: "default"; set(v) { sp().edit().putString("active", v).apply() }
    var backend: String get() = sp().getString("backend", "none") ?: "none"; set(v) { sp().edit().putString("backend", v).apply() }
    var tunnel: String get() = sp().getString("tunnel", "off") ?: "off"; set(v) { sp().edit().putString("tunnel", v).apply() }
    fun domain(site: String) = sp().getString("dom_$site", "") ?: ""
    fun setDomain(site: String, d: String) { sp().edit().putString("dom_$site", d.trim().lowercase()).apply() }

    fun root(): File {
        val d = File(Environment.getExternalStorageDirectory(), "HostPot")
        d.mkdirs()
        if (d.exists() && d.canWrite()) return d
        return File(app.getExternalFilesDir(null) ?: app.filesDir, "HostPot").also { it.mkdirs() }
    }
    fun sitesDir() = File(root(), "sites").also { it.mkdirs() }
    fun runDir() = File(root(), "run").also { it.mkdirs() }
    fun logs() = File(root(), "logs").also { it.mkdirs() }
    fun siteDir(n: String) = File(sitesDir(), n)
    fun sites(): List<String> = (sitesDir().listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()).sorted()

    fun createSite(raw: String): String? {
        val n = raw.trim().replace(Regex("[^A-Za-z0-9_-]"), "")
        if (n.isEmpty()) return null
        val d = siteDir(n); d.mkdirs(); File(d, "htaccess").mkdirs()
        val idx = File(d, "index.html")
        if (!idx.exists()) idx.writeText("<html><head><title>$n</title></head><body><h1>It works!</h1><p>Site <b>$n</b> is served from this phone by HostPot.</p></body></html>")
        return n
    }
    fun ensureDefault() { if (sites().isEmpty()) createSite("default"); if (!sites().contains(active)) active = sites().first() }
    fun siteForHost(h: String?): String? {
        if (h == null) return null
        return sites().firstOrNull { domain(it) == h.lowercase() || it == h }
    }
}

object Logx {
    private val buf = ArrayDeque<String>()
    fun add(s: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + s
        synchronized(buf) { buf.addLast(line); if (buf.size > 300) buf.removeFirst() }
        try {
            val f = File(Cfg.logs(), "server.log")
            if (f.length() > 1_000_000) f.delete()
            f.appendText(line + "\n")
        } catch (_: Exception) {}
    }
    fun tail(n: Int): String = synchronized(buf) { buf.toList().takeLast(n).joinToString("\n") }
}
