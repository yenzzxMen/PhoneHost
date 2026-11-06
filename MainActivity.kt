package com.phonehost.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var files: TextView
    private lateinit var startBtn: Button
    private lateinit var portEdit: EditText
    private val handler = Handler(Looper.getMainLooper())

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Thread {
            val n = SiteManager.importFiles(this, uris)
            runOnUiThread { toast("$n file(s) add hui"); refresh() }
        }.start()
    }

    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Thread {
            val msg = try { "ZIP import done (${SiteManager.importZip(this, uri)} files)" }
            catch (e: Exception) { "ZIP error: ${e.message}" }
            runOnUiThread { toast(msg); refresh() }
        }.start()
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SiteManager.ensureDefault(this)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    // ---------- UI ----------
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        root.addView(title("PhoneHost"))
        root.addView(text("Apne phone se apni site host karo."))

        // --- Server ---
        root.addView(section("1. Server"))
        status = text("")
        root.addView(status)
        portEdit = edit("Port (1024-65535)", Prefs.port(this).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(portEdit)
        startBtn = button("Start") { toggleServer() }
        root.addView(startBtn)
        root.addView(button("Browser me site kholo") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:${Prefs.port(this)}")))
        })
        val sw = Switch(this).apply {
            text = "Phone restart hone par auto-start"
            isChecked = Prefs.autoStart(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAutoStart(this@MainActivity, v) }
        }
        root.addView(sw)

        // --- Site files ---
        root.addView(section("2. Site files"))
        root.addView(button("Files upload karo") { filePicker.launch(arrayOf("*/*")) })
        root.addView(button("Site ZIP upload karo (poori site replace)") { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) })
        root.addView(button("Site clear karo") { SiteManager.clear(this); refresh() })
        files = text("")
        root.addView(files)

        // --- 24x7 ---
        root.addView(section("3. 24x7 mode"))
        root.addView(text("Charger lagao, battery optimization band karo. Xiaomi/Oppo/Vivo me 'Autostart' aur 'No restrictions' bhi on karo."))
        root.addView(button("Battery optimization off karo") { requestIgnoreBattery() })
        root.addView(button("App settings kholo") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        // --- Public URL ---
        root.addView(section("4. Public URL (Cloudflare Tunnel via Termux)"))
        root.addView(text(
            "Pehli baar setup:\n" +
            "a) Termux (F-Droid) install karo.\n" +
            "b) Termux me ye chalao:\n   echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
            "c) Termux band karke dobara kholo, aur Android Settings > Apps > PhoneHost > Permissions me 'Run commands in Termux' allow karo.\n" +
            "d) Neeche 'Termux setup' dabao."
        ))
        root.addView(button("Termux setup (python + cloudflared)") { termux(TermuxBridge.SETUP_CMD) })
        val tokenEdit = edit("Cloudflare tunnel token (optional, apne domain ke liye)", Prefs.token(this))
        root.addView(tokenEdit)
        root.addView(button("Public tunnel start karo") {
            Prefs.setToken(this, tokenEdit.text.toString().trim())
            termux(TermuxBridge.tunnelCmd(Prefs.port(this), Prefs.token(this)))
            toast("Termux me trycloudflare.com wala URL dikhega")
        })
        root.addView(text("Token khali = free random URL (https://xyz.trycloudflare.com). Token ho = aapka apna domain (Cloudflare Zero Trust > Tunnels se token lo)."))

        // --- Python ---
        root.addView(section("5. Python / Termux command"))
        val cmdEdit = edit("Command", Prefs.termuxCmd(this))
        root.addView(cmdEdit)
        root.addView(button("Termux me chalao") {
            Prefs.setTermuxCmd(this, cmdEdit.text.toString())
            termux(cmdEdit.text.toString())
        })

        // --- Router guide ---
        root.addView(section("6. Port forwarding (Wi-Fi router)"))
        root.addView(text(routerGuide()))

        // --- Update ---
        root.addView(section("7. Update"))
        root.addView(text("Version: ${BuildConfig.VERSION_NAME}"))
        root.addView(button("Update check karo") { checkUpdate() })

        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        val ip = localIp()
        val port = Prefs.port(this)
        status.text = if (ServerService.running) {
            "● RUNNING\nLocal: http://localhost:$port\nWi-Fi: http://${ip ?: "?"}:$port\nRequests served: ${WebServer.requestCount}"
        } else {
            "○ STOPPED" + (ServerService.lastError?.let { "\n$it" } ?: "")
        }
        startBtn.text = if (ServerService.running) "Stop" else "Start"
        files.text = SiteManager.list(this)
    }

    // ---------- actions ----------
    private fun toggleServer() {
        val i = Intent(this, ServerService::class.java)
        if (ServerService.running) {
            stopService(i)
        } else {
            val p = portEdit.text.toString().toIntOrNull()
            if (p == null || p !in 1024..65535) { toast("Port 1024-65535 ke beech daalo"); return }
            Prefs.setPort(this, p)
            ContextCompat.startForegroundService(this, i)
        }
        handler.postDelayed({ refresh() }, 600)
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Already off hai ✔"); return }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun termux(cmd: String) {
        TermuxBridge.run(this, cmd)?.let { toast(it) }
    }

    private fun checkUpdate() {
        toast("Check ho raha hai…")
        UpdateChecker.check { r ->
            runOnUiThread {
                val b = android.app.AlertDialog.Builder(this).setMessage(r.message)
                if (r.downloadUrl != null) {
                    b.setPositiveButton("Download APK") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.downloadUrl)))
                    }
                    b.setNegativeButton("Baad me", null)
                } else b.setPositiveButton("OK", null)
                b.show()
            }
        }
    }

    private fun routerGuide(): String {
        val ip = localIp() ?: "<phone-ip>"
        return "Agar Wi-Fi par ho:\n" +
            "1. Phone ko router me fixed IP do (DHCP reservation): $ip\n" +
            "2. Browser me router kholo (usually 192.168.1.1 ya 192.168.0.1).\n" +
            "3. Port Forwarding / Virtual Server me jao.\n" +
            "4. External port ${Prefs.port(this)} → Internal IP $ip, port ${Prefs.port(this)}, TCP.\n" +
            "5. Apna public IP dekho (whatismyip.com) aur http://PUBLIC_IP:${Prefs.port(this)} try karo.\n\n" +
            "Note: Mobile data (Jio/Airtel) par CGNAT hota hai, wahan port forwarding nahi chalta. Wahan upar wala Cloudflare Tunnel use karo."
    }

    // ---------- helpers ----------
    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) { null }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun title(s: String) = TextView(this).apply { text = s; textSize = 28f; setPadding(0, 0, 0, dp(4)) }
    private fun section(s: String) = TextView(this).apply {
        text = s; textSize = 18f; setPadding(0, dp(24), 0, dp(8))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun text(s: String) = TextView(this).apply { text = s; textSize = 14f; setPadding(0, dp(4), 0, dp(4)) }
    private fun edit(hint: String, value: String) = EditText(this).apply { this.hint = hint; setText(value); setSingleLine(true) }
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }
}
