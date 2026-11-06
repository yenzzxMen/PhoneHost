package com.phonehost.app

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var filesView: TextView
    private lateinit var startBtn: Button
    private lateinit var portEdit: EditText
    private val handler = Handler(Looper.getMainLooper())

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            Thread {
                val n = SiteManager.importFiles(this, uris)
                runOnUiThread {
                    toast("$n file(s) added")
                    refresh()
                }
            }.start()
        }
    }

    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Thread {
                val msg = try {
                    "ZIP imported (" + SiteManager.importZip(this, uri) + " files)"
                } catch (e: Exception) {
                    "ZIP error: " + e.message
                }
                runOnUiThread {
                    toast(msg)
                    refresh()
                }
            }.start()
        }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SiteManager.ensureDefault(this)
        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

    // ================= UI =================
    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(20), dp(16), dp(40))

        val title = TextView(this)
        title.text = "PhoneHost"
        title.textSize = 30f
        title.setTypeface(title.typeface, Typeface.BOLD)
        root.addView(title)
        root.addView(label("Host your own website from your phone."))

        // ---------- Status / server ----------
        statusCard = LinearLayout(this)
        statusCard.orientation = LinearLayout.VERTICAL
        statusCard.setPadding(dp(16), dp(16), dp(16), dp(16))
        val slp = full()
        slp.topMargin = dp(14)
        statusCard.layoutParams = slp

        statusTitle = TextView(this)
        statusTitle.textSize = 20f
        statusTitle.setTextColor(Color.WHITE)
        statusTitle.setTypeface(statusTitle.typeface, Typeface.BOLD)
        statusCard.addView(statusTitle)

        statusDetail = TextView(this)
        statusDetail.textSize = 14f
        statusDetail.setTextColor(Color.WHITE)
        statusDetail.setPadding(0, dp(6), 0, dp(6))
        statusCard.addView(statusDetail)

        portEdit = EditText(this)
        portEdit.hint = "Port (1024-65535)"
        portEdit.setText(Prefs.port(this).toString())
        portEdit.inputType = InputType.TYPE_CLASS_NUMBER
        portEdit.setTextColor(Color.WHITE)
        portEdit.setHintTextColor(0xCCFFFFFF.toInt())
        portEdit.layoutParams = full()
        statusCard.addView(portEdit)

        startBtn = btn("Start", false) { toggleServer() }
        statusCard.addView(startBtn)
        statusCard.addView(btn("Open site in browser", false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:" + Prefs.port(this))))
        })
        statusCard.addView(btn("Share link", false) { shareLink() })

        val sw = Switch(this)
        sw.text = "Auto-start after phone reboot"
        sw.setTextColor(Color.WHITE)
        sw.isChecked = Prefs.autoStart(this)
        sw.setOnCheckedChangeListener { _, checked -> Prefs.setAutoStart(this, checked) }
        val swlp = full()
        swlp.topMargin = dp(8)
        sw.layoutParams = swlp
        statusCard.addView(sw)
        root.addView(statusCard)

        // ---------- Site files ----------
        val siteCard = card("Site files")
        siteCard.addView(btn("Upload files", true) { filePicker.launch(arrayOf("*/*")) })
        siteCard.addView(btn("Upload site ZIP (replaces whole site)", false) { zipPicker.launch(arrayOf("*/*")) })
        siteCard.addView(btn("Clear site", false) {
            SiteManager.clear(this)
            refresh()
        })
        filesView = label("")
        siteCard.addView(filesView)
        root.addView(siteCard)

        // ---------- PHP ----------
        val phpCard = card("PHP sites (via Termux)")
        phpCard.addView(label("For .php sites. PhoneHost runs PHP inside Termux and forwards visitors to it. Keep Termux running."))
        val phpSw = Switch(this)
        phpSw.text = "PHP mode"
        phpSw.isChecked = Prefs.phpMode(this)
        phpSw.setOnCheckedChangeListener { _, checked ->
            Prefs.setPhpMode(this, checked)
            toast(if (checked) "PHP mode ON" else "PHP mode OFF")
        }
        phpCard.addView(phpSw)
        phpCard.addView(btn("1. Install PHP in Termux", false) { termux(TermuxBridge.PHP_SETUP_CMD) })
        phpCard.addView(btn("2. Start PHP server", true) { startPhp(false) })
        phpCard.addView(btn("Re-sync files and start (overwrites PHP data)", false) { startPhp(true) })
        phpCard.addView(label("Start PHP server keeps data created by your site (settings, uploads). Re-sync replaces everything with the files uploaded here. .htaccess rewrite rules are not supported."))
        root.addView(phpCard)

        // ---------- Public URL ----------
        val pubCard = card("Public URL (Cloudflare Tunnel)")
        pubCard.addView(label(
            "One-time Termux setup:\n" +
            "a) Install Termux from F-Droid.\n" +
            "b) In Termux run:\n   echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
            "c) Close Termux fully and reopen. Then Android Settings > Apps > PhoneHost > Permissions > allow 'Run commands in Termux'.\n" +
            "d) Tap 'Termux setup' below."
        ))
        pubCard.addView(btn("Termux setup (python + cloudflared)", false) { termux(TermuxBridge.SETUP_CMD) })
        val tokenEdit = field("Cloudflare tunnel token (optional, for your own domain)", Prefs.token(this))
        pubCard.addView(tokenEdit)
        pubCard.addView(btn("Start public tunnel", true) {
            Prefs.setToken(this, tokenEdit.text.toString().trim())
            termux(TermuxBridge.tunnelCmd(Prefs.port(this), Prefs.token(this)))
            toast("The trycloudflare.com URL will appear in Termux")
        })
        pubCard.addView(label("No token = free random URL. Copy it from Termux and paste it below to save it here."))
        val urlEdit = field("Your public URL (https://...)", Prefs.publicUrl(this))
        pubCard.addView(urlEdit)
        pubCard.addView(btn("Save and copy URL", false) {
            val u = urlEdit.text.toString().trim()
            Prefs.setPublicUrl(this, u)
            if (u.isNotEmpty()) copy(u)
            refresh()
        })
        root.addView(pubCard)

        // ---------- 24x7 ----------
        val keepCard = card("24x7 mode")
        keepCard.addView(label("Keep the phone charging and turn off battery optimization. On Xiaomi/Oppo/Vivo also enable Autostart and set Battery to No restrictions."))
        keepCard.addView(btn("Turn off battery optimization", true) { requestIgnoreBattery() })
        keepCard.addView(btn("Open app settings", false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
        })
        root.addView(keepCard)

        // ---------- Python / custom ----------
        val pyCard = card("Python / custom Termux command")
        val cmdEdit = field("Command", Prefs.termuxCmd(this))
        pyCard.addView(cmdEdit)
        pyCard.addView(btn("Run in Termux", true) {
            Prefs.setTermuxCmd(this, cmdEdit.text.toString())
            termux(cmdEdit.text.toString())
        })
        root.addView(pyCard)

        // ---------- Router ----------
        val routerCard = card("Port forwarding (Wi-Fi router)")
        routerCard.addView(label(routerGuide()))
        root.addView(routerCard)

        // ---------- Update ----------
        val upCard = card("About and updates")
        upCard.addView(label("Version: " + BuildConfig.VERSION_NAME))
        upCard.addView(btn("Check for updates", false) { checkUpdate() })
        root.addView(upCard)

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun refresh() {
        val ip = localIp()
        val ipText = if (ip != null) ip else "?"
        val port = Prefs.port(this)
        val running = ServerService.running
        statusCard.background = roundBg(if (running) 0xFF1F7A45.toInt() else 0xFF4A4F5C.toInt(), 16)
        if (running) {
            statusTitle.text = "Server is running"
            val sb = StringBuilder()
            sb.append("Local: http://localhost:").append(port).append("\n")
            sb.append("Wi-Fi: http://").append(ipText).append(":").append(port).append("\n")
            val pu = Prefs.publicUrl(this)
            if (pu.isNotEmpty()) sb.append("Public: ").append(pu).append("\n")
            sb.append("Requests served: ").append(WebServer.requestCount)
            if (Prefs.phpMode(this)) sb.append("\nPHP mode: ON")
            statusDetail.text = sb.toString()
            startBtn.text = "Stop"
        } else {
            statusTitle.text = "Server is stopped"
            val err = ServerService.lastError
            statusDetail.text = if (err != null) err else "Tap Start to serve your site."
            startBtn.text = "Start"
        }
        filesView.text = SiteManager.list(this)
    }

    // ================= actions =================
    private fun toggleServer() {
        val i = Intent(this, ServerService::class.java)
        if (ServerService.running) {
            stopService(i)
        } else {
            val p = portEdit.text.toString().toIntOrNull()
            if (p == null || p < 1024 || p > 65535) {
                toast("Enter a port between 1024 and 65535")
                return
            }
            Prefs.setPort(this, p)
            ContextCompat.startForegroundService(this, i)
        }
        handler.postDelayed({ refresh() }, 600)
    }

    private fun startPhp(overwrite: Boolean) {
        if (!ServerService.running) {
            toast("Start the server first")
            return
        }
        termux(TermuxBridge.phpCmd(Prefs.port(this), Prefs.syncToken(this), Prefs.PHP_PORT, overwrite))
        toast("PHP server starting in Termux. Keep Termux running.")
    }

    private fun shareLink() {
        val pu = Prefs.publicUrl(this)
        val link = if (pu.isNotEmpty()) pu else "http://" + (localIp() ?: "localhost") + ":" + Prefs.port(this)
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, link)
        startActivity(Intent.createChooser(i, "Share link"))
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PhoneHost", text))
        toast("Copied")
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Already turned off")
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName)))
    }

    private fun termux(cmd: String) {
        val err = TermuxBridge.run(this, cmd)
        if (err != null) toast(err)
    }

    private fun checkUpdate() {
        toast("Checking...")
        UpdateChecker.check { r ->
            runOnUiThread {
                val b = AlertDialog.Builder(this)
                b.setMessage(r.message)
                val link = r.downloadUrl
                if (link != null) {
                    b.setPositiveButton("Download APK") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    }
                    b.setNegativeButton("Later", null)
                } else {
                    b.setPositiveButton("OK", null)
                }
                b.show()
            }
        }
    }

    private fun routerGuide(): String {
        val ip = localIp() ?: "<phone-ip>"
        val port = Prefs.port(this)
        return "If you are on Wi-Fi:\n" +
            "1. Give the phone a fixed IP in your router (DHCP reservation): " + ip + "\n" +
            "2. Open your router page in a browser (usually 192.168.1.1 or 192.168.0.1).\n" +
            "3. Go to Port Forwarding / Virtual Server.\n" +
            "4. External port " + port + " -> Internal IP " + ip + ", port " + port + ", TCP.\n" +
            "5. Find your public IP (whatismyip.com) and try http://PUBLIC_IP:" + port + "\n\n" +
            "Note: mobile data (Jio/Airtel) uses CGNAT, so port forwarding does not work there. Use the Cloudflare Tunnel above."
    }

    // ================= helpers =================
    private fun localIp(): String? {
        return try {
            val list = NetworkInterface.getNetworkInterfaces().toList()
            var found: String? = null
            for (ni in list) {
                for (addr in ni.inetAddresses.toList()) {
                    if (found == null && !addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        found = addr.hostAddress
                    }
                }
            }
            found
        } catch (e: Exception) {
            null
        }
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun full(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun card(titleText: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = roundBg(if (isDark()) 0xFF262B36.toInt() else 0xFFF0F2F7.toInt(), 16)
        c.setPadding(dp(16), dp(14), dp(16), dp(16))
        val lp = full()
        lp.topMargin = dp(14)
        c.layoutParams = lp
        val t = TextView(this)
        t.text = titleText
        t.textSize = 17f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setPadding(0, 0, 0, dp(8))
        c.addView(t)
        return c
    }

    private fun label(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 14f
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun field(hintText: String, value: String): EditText {
        val e = EditText(this)
        e.hint = hintText
        e.setText(value)
        e.setSingleLine(true)
        e.layoutParams = full()
        return e
    }

    private fun btn(labelText: String, primary: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = labelText
        b.isAllCaps = false
        val dark = isDark()
        if (primary) {
            b.setTextColor(Color.WHITE)
            b.background = roundBg(0xFF3D7BFF.toInt(), 12)
        } else {
            b.setTextColor(if (dark) Color.WHITE else 0xFF1B2330.toInt())
            b.background = roundBg(if (dark) 0xFF3A4152.toInt() else 0xFFDDE3F0.toInt(), 12)
        }
        val lp = full()
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }
}
