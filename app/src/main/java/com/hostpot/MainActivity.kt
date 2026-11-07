package com.hostpot

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var info: TextView? = null
    private var logView: TextView? = null
    private var tab = 0
    private var pendingDir: File? = null
    private var extractZip = true
    private val tabs = listOf("SERVER", "SITES", "TERMUX", "NETWORK", "LOGS", "SETTINGS")
    private val GRAY = 0xFFF8F9FA.toInt()

    // ---------- UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(s: String, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(Color.BLACK); textSize = 13f
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        setPadding(dp(6), dp(4), dp(6), dp(4))
    }
    private fun box(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(fill); setStroke(dp(1).coerceAtLeast(1), stroke)
    }
    private fun bevel(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), box(0xFFC8CCD1.toInt(), 0xFF72777D.toInt()))
        addState(intArrayOf(), box(0xFFEAECF0.toInt(), 0xFFA2A9B1.toInt()))
    }
    private fun sunken(): Drawable = box(Color.WHITE, 0xFFA2A9B1.toInt())
    private fun btn(s: String, f: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(Color.BLACK); typeface = Typeface.MONOSPACE; textSize = 12f
        stateListAnimator = null; minimumHeight = dp(38); minHeight = dp(38); background = bevel()
        setOnClickListener { f() }
    }
    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in v) addView(x, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }
    private fun add(v: View) = content.addView(v, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(3), dp(6), dp(3)) })
    private fun panel(s: String) = tv(s).apply { background = sunken(); setPadding(dp(8), dp(8), dp(8), dp(8)) }
    private fun edit(hint: String, v: String) = EditText(this).apply {
        setText(v); this.hint = hint; setTextColor(Color.BLACK); typeface = Typeface.MONOSPACE; textSize = 13f
        background = sunken(); setPadding(dp(8), dp(8), dp(8), dp(8)); setSingleLine(true)
    }
    private fun check(s: String, v: Boolean) = CheckBox(this).apply { text = s; isChecked = v; setTextColor(Color.BLACK); typeface = Typeface.MONOSPACE }
    private fun head(s: String) = tv("== $s ==", true)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun bar(f: Double): String { val n = (f.coerceIn(0.0, 1.0) * 20).toInt(); return "[" + "#".repeat(n) + "-".repeat(20 - n) + "]" }

    // ---------- data helpers ----------
    private fun running() = HostService.server?.alive == true
    private fun ips(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress }.map { it.hostAddress ?: "" }
    } catch (e: Exception) { emptyList() }
    private fun tunnelUrl(): String {
        return try {
            val t = File(Cfg.logs(), "tunnel.log").readText()
            Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").findAll(t).lastOrNull()?.value ?: "(none)"
        } catch (e: Exception) { "(none)" }
    }
    private fun hasAll(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun uptime(): String {
        if (Stats.start == 0L) return "--"
        val s = (System.currentTimeMillis() - Stats.start) / 1000
        return String.format("%dd %02d:%02d:%02d", s / 86400, s % 86400 / 3600, s % 3600 / 60, s % 60)
    }
    private fun mb(b: Long) = String.format("%.1f MB", b / 1048576.0)
    private fun infoText(): String {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        val ramUsed = 1.0 - mi.availMem.toDouble() / mi.totalMem
        val st = StatFs(Cfg.root().absolutePath)
        val stUsed = 1.0 - st.availableBytes.toDouble() / st.totalBytes
        val bat = (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val ip = ips().firstOrNull() ?: "no-wifi"
        return "SERVER : ${if (running()) "** RUNNING **" else "STOPPED"}\n" +
            "PORT   : ${Cfg.port}   BACKEND: ${Cfg.backendPort}\n" +
            "SITE   : ${Cfg.active}\n" +
            "LOCAL  : http://$ip:${Cfg.port}\n" +
            "TUNNEL : ${tunnelUrl()}\n" +
            "UPTIME : ${uptime()}   RESTARTS: ${Stats.restarts}\n" +
            "HITS   : ${Stats.req.get()}   OUT: ${mb(Stats.bytes.get())}\n" +
            "BACKEND: ${Cfg.backend} ${if (Cfg.backend != "none") (if (Stats.bUp) "(up)" else "(down)") else ""}   TUNNEL-MODE: ${Cfg.tunnel}\n" +
            "SITES  : ${Stats.sites.entries.sortedByDescending { it.value.get() }.take(4).joinToString("  ") { it.key + "=" + it.value.get() }.ifEmpty { "-" }}\n\n" +
            "RAM    ${bar(ramUsed)} ${(ramUsed * 100).toInt()}%\n" +
            "DISK   ${bar(stUsed)} ${(stUsed * 100).toInt()}%\n" +
            "BATT   ${bar(bat / 100.0)} $bat%\n\n" +
            "ROOT   : ${Cfg.root().absolutePath}" +
            (if (!hasAll()) "\nWARNING: all-files access is off" else "")
    }

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.app = applicationContext
        Cfg.ensureDefault()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(GRAY) }
        root.addView(TextView(this).apply {
            text = " HostPot - Phone Web Server"; setTextColor(Color.BLACK); setBackgroundColor(0xFFEAECF0.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 14f; setPadding(dp(6), dp(8), dp(6), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
        val tl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.forEachIndexed { i, n -> tl.addView(btn(n) { show(i) }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
        root.addView(HorizontalScrollView(this).apply { addView(tl) }, LinearLayout.LayoutParams(-1, -2))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        status = tv("").apply { background = sunken(); textSize = 11f }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        setContentView(root)
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        else if (Build.VERSION.SDK_INT < 30) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (checkSelfPermission(Termux.PERM) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(Termux.PERM)
        if (need.isNotEmpty()) try { requestPermissions(need.toTypedArray(), 1) } catch (_: Exception) {}
        show(0)
        h.postDelayed(poll, 300)
        if (Cfg.autoUpdate) Updater.check(this, false)
    }

    private val poll: Runnable = object : Runnable {
            override fun run() {
                status.text = " SERVER: ${if (running()) "RUNNING" else "STOPPED"} | :${Cfg.port} | HITS ${Stats.req.get()} | UP ${uptime()}"
                info?.text = infoText()
                logView?.text = Logx.tail(60)
                if (Cfg.backend != "none") Thread { Stats.bUp = Termux.portOpen(Cfg.backendPort) }.start()
                h.postDelayed(this, 2000)
            }
    }

    override fun onResume() { super.onResume(); if (Cfg.autoUpdate) Updater.check(this, false) }
    override fun onDestroy() { h.removeCallbacks(poll); super.onDestroy() }

    private fun show(i: Int) {
        tab = i; info = null; logView = null
        content.removeAllViews()
        when (i) { 0 -> tabServer(); 1 -> tabSites(); 2 -> tabTermux(); 3 -> tabNet(); 4 -> tabLogs(); else -> tabSettings() }
    }

    // ---------- tabs ----------
    private fun tabServer() {
        add(head("SERVER CONTROL"))
        info = panel(infoText()); add(info!!)
        add(row(btn("START") { HostService.start(this); toast("starting...") },
            btn("STOP") { HostService.stop(this) },
            btn("RESTART") { HostService.stop(this); h.postDelayed({ HostService.start(this) }, 900) }))
        add(row(btn("OPEN SITE") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${Cfg.port}"))) },
            btn("ALL-FILES ACCESS") { askStorage() }))
        add(btn("DISABLE BATTERY OPTIMIZATION") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) toast("Already unrestricted")
            else startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
    }

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        else requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
    }

    private fun tabSites() {
        add(head("SITES  (${Cfg.sitesDir().absolutePath})"))
        val nm = edit("new site name", "")
        add(nm)
        add(btn("CREATE SITE") {
            val n = Cfg.createSite(nm.text.toString()); if (n == null) toast("invalid name") else { toast("created $n"); show(1) }
        })
        val cz = check("Extract .zip on upload", extractZip)
        cz.setOnCheckedChangeListener { _, v -> extractZip = v }
        add(cz)
        for (s in Cfg.sites()) {
            val act = s == Cfg.active
            add(tv((if (act) "[ACTIVE] " else "") + s + (Cfg.domain(s).let { if (it.isEmpty()) "" else "  ($it)" }), true).apply { background = sunken() })
            add(row(btn("ACTIVE") { Cfg.active = s; toast("$s is now active"); show(1) },
                btn("FILES") { listFiles(s) },
                btn("OPEN") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${Cfg.port}/s/$s/"))) }))
            add(row(btn("UPLOAD") { pickFiles(Cfg.siteDir(s)) },
                btn("UPLOAD>htaccess") { pickFiles(File(Cfg.siteDir(s), "htaccess")) },
                btn("DOMAIN") { setDomain(s) }))
            add(btn("DELETE SITE") {
                AlertDialog.Builder(this).setMessage("Delete $s and all files?").setPositiveButton("Delete") { _, _ ->
                    Cfg.siteDir(s).deleteRecursively(); Cfg.ensureDefault(); show(1)
                }.setNegativeButton("Cancel", null).show()
            })
        }
    }

    private fun listFiles(s: String) {
        val d = Cfg.siteDir(s)
        val fl = d.walkTopDown().filter { it.isFile }.take(300).toList()
        if (fl.isEmpty()) { AlertDialog.Builder(this).setTitle("FILES: $s").setMessage("(empty)").setPositiveButton("OK", null).show(); return }
        val names = fl.map { it.relativeTo(d).path + "  (" + it.length() / 1024 + " KB)" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("FILES: $s  (tap = delete)").setItems(names) { _, i ->
            AlertDialog.Builder(this).setMessage("Delete ${fl[i].relativeTo(d).path} ?").setPositiveButton("Delete") { _, _ ->
                fl[i].delete(); toast("deleted"); listFiles(s)
            }.setNegativeButton("Cancel", null).show()
        }.setPositiveButton("Close", null).show()
    }

    private fun setDomain(s: String) {
        val e = edit("example.com", Cfg.domain(s))
        AlertDialog.Builder(this).setTitle("Domain for $s").setView(e).setPositiveButton("Save") { _, _ ->
            Cfg.setDomain(s, e.text.toString()); show(1)
        }.show()
    }

    private fun pickFiles(dir: File) {
        pendingDir = dir
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 11)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 11 || res != RESULT_OK || data == null) return
        val dir = pendingDir ?: return
        val uris = ArrayList<Uri>()
        data.clipData?.let { for (i in 0 until it.itemCount) uris.add(it.getItemAt(i).uri) }
        data.data?.let { uris.add(it) }
        Thread {
            var n = 0
            for (u in uris) { try { copyIn(u, dir); n++ } catch (e: Exception) { Logx.add("upload fail: " + e.message) } }
            runOnUiThread { toast("$n file(s) uploaded") }
        }.start()
    }

    private fun copyIn(uri: Uri, dir: File) {
        var name = "file"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) }
        }
        dir.mkdirs()
        name = name.replace("/", "_")
        if (extractZip && dir.name != "htaccess" && name.lowercase().endsWith(".zip")) {
            ZipInputStream(contentResolver.openInputStream(uri)!!).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val o = File(dir, e.name)
                    if (o.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                        if (e.isDirectory) o.mkdirs() else { o.parentFile?.mkdirs(); FileOutputStream(o).use { z.copyTo(it) } }
                    }
                    e = z.nextEntry
                }
            }
            Logx.add("zip extracted: $name -> ${dir.name}")
            return
        }
        contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(File(dir, name)).use { i.copyTo(it) } }
        Logx.add("uploaded: $name -> ${dir.name}")
    }

    private fun tx(cmd: String, bg: Boolean = true) {
        if (Termux.run(this, cmd, bg)) toast("sent to Termux")
        else toast("Termux command failed. Check Termux permission and allow-external-apps.")
    }
    private fun okTx(ok: Boolean) { if (ok) toast("sent to Termux") else toast("Termux command failed. Check Termux permission and allow-external-apps.") }

    private fun tabTermux() {
        add(head("TERMUX (PHP / Node / Python)"))
        add(panel("BACKEND     : ${Cfg.backend}\nACTIVE SITE : ${Cfg.active}\nBACKEND PORT: ${Cfg.backendPort}"))
        add(btn("COPY SETUP COMMAND") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("setup", Termux.SETUP))
            toast("copied")
        })
        add(btn("OPEN TERMUX") {
            val i = packageManager.getLaunchIntentForPackage("com.termux")
            if (i == null) toast("Termux is not installed") else startActivity(i)
        })
        add(btn("INSTALL PACKAGES") { okTx(Termux.install(this)) })
        fun go(k: String) { Cfg.backend = k; okTx(Termux.startBackend(this, k, Cfg.active)); show(2) }
        add(row(btn("START PHP") { go("php") }, btn("START NODE") { go("node") }))
        add(row(btn("START PYTHON") { go("python") },
            btn("STOP BACKENDS") { Cfg.backend = "none"; okTx(Termux.stopAllBackends(this)); show(2) }))
        val c = edit("custom termux command", "")
        add(c)
        add(btn("RUN CUSTOM COMMAND") { tx(c.text.toString()) })
    }

    private fun showMsg(t: String) = runOnUiThread { AlertDialog.Builder(this).setMessage(t).setPositiveButton("OK", null).show() }

    private fun tabNet() {
        add(head("NETWORK"))
        add(panel("LOCAL : " + (ips().joinToString("  ") { "http://$it:${Cfg.port}" }.ifEmpty { "(no network)" }) + "\nTUNNEL: " + tunnelUrl() + " [mode: ${Cfg.tunnel}]"))
        add(btn("SHOW PUBLIC IP") {
            Thread {
                val r = try { URL("https://api.ipify.org").readText() } catch (e: Exception) { "error: ${e.message}" }
                showMsg("Public IP: $r\n\nIf this differs from the router WAN IP, or starts with 100.64, the connection is behind CGNAT and port forwarding will not work.")
            }.start()
        })
        add(tv("PORT FORWARDING (UPnP)", true))
        add(row(btn("OPEN PORT ${Cfg.port}") { toast("contacting router..."); Thread { showMsg(Upnp.open(Cfg.port)) }.start() },
            btn("CLOSE PORT") { Thread { showMsg(Upnp.close(Cfg.port)) }.start() }))
        add(tv("CLOUDFLARE TUNNEL", true))
        add(btn("START QUICK TUNNEL") { Cfg.tunnel = "quick"; okTx(Termux.startTunnel(this, "quick")); show(3) })
        add(btn("START TOKEN TUNNEL") {
            if (Cfg.token.isEmpty()) toast("Set the Cloudflare token in SETTINGS")
            else { Cfg.tunnel = "token"; okTx(Termux.startTunnel(this, "token")); show(3) }
        })
        add(btn("STOP TUNNEL") { Cfg.tunnel = "off"; okTx(Termux.stop(this, "tunnel")); show(3) })
        add(btn("REFRESH") { show(3) })
    }

    private fun tabLogs() {
        add(head("LOG"))
        logView = panel(Logx.tail(60)); add(logView!!)
        add(btn("CLEAR") { try { File(Cfg.logs(), "server.log").delete() } catch (_: Exception) {}; toast("file log cleared") })
        add(tv("Backend logs: ${Cfg.logs().absolutePath}/php.log node.log py.log tunnel.log"))
    }

    private fun tabSettings() {
        add(head("SETTINGS"))
        val p = edit("port", Cfg.port.toString()).apply { inputType = 2 }
        val bp = edit("backend port", Cfg.backendPort.toString()).apply { inputType = 2 }
        val tk = edit("cloudflare tunnel token", Cfg.token)
        val ab = check("Auto start on phone boot", Cfg.autoBoot)
        val au = check("Check for updates on start", Cfg.autoUpdate)
        add(tv("Server port (1024+):")); add(p)
        add(tv("Backend port:")); add(bp)
        add(tv("Cloudflare tunnel token:")); add(tk)
        add(ab); add(au)
        add(btn("SAVE (restart server to apply)") {
            Cfg.port = p.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
            Cfg.backendPort = bp.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 8081
            Cfg.token = tk.text.toString().trim(); Cfg.autoBoot = ab.isChecked; Cfg.autoUpdate = au.isChecked
            toast("saved")
        })
        add(btn("CHECK FOR UPDATE NOW") { Updater.check(this, true) })
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        add(tv("Version $v   repo: ${Cfg.REPO}"))
    }
}
