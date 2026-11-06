package com.phonehost.app

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.graphics.Typeface
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
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var filesView: TextView
    private lateinit var setupView: TextView
    private lateinit var updateView: TextView
    private lateinit var startBtn: Button
    private lateinit var portEdit: EditText
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var phpUp = false
    @Volatile private var tunnelHost: String? = null
    @Volatile private var filesText = "(loading...)"
    private val busy = AtomicBoolean(false)

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            Thread {
                val n = SiteManager.importFiles(this, uris)
                runOnUiThread {
                    toast("$n file(s) added")
                    probe()
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
                    probe()
                }
            }.start()
        }
    }

    private val termuxPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        toast(
            if (ok) "Termux permission granted"
            else "Termux permission NOT granted. Termux must be installed BEFORE PhoneHost. Install Termux, then reinstall PhoneHost."
        )
        refresh()
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askTermuxPerm(false)
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            probe()
            handler.postDelayed(this, 2500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SiteManager.ensureDefault(this)
        setContentView(buildUi())
        refresh()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askTermuxPerm(false)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ================= UI (plain, old style) =================
    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(20), dp(16), dp(40))

        val title = TextView(this)
        title.text = "PhoneHost"
        title.textSize = 30f
        root.addView(title)
        root.addView(label("Host your own website from your phone."))

        // ---------- 1. Server ----------
        root.addView(heading("1. Server"))
        statusView = label("")
        root.addView(statusView)

        portEdit = EditText(this)
        portEdit.hint = "Port (1024-65535, not 8081)"
        portEdit.setText(Prefs.port(this).toString())
        portEdit.inputType = InputType.TYPE_CLASS_NUMBER
        portEdit.layoutParams = full()
        root.addView(portEdit)

        startBtn = btn("Start") { toggleServer() }
        root.addView(startBtn)
        root.addView(btn("Open site in browser") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:" + Prefs.port(this))))
        })
        root.addView(btn("Share link") { shareLink() })

        val sw = Switch(this)
        sw.text = "Auto-start after phone reboot"
        sw.isChecked = Prefs.autoStart(this)
        sw.setOnCheckedChangeListener { _, checked -> Prefs.setAutoStart(this, checked) }
        val swlp = full()
        swlp.topMargin = dp(8)
        sw.layoutParams = swlp
        root.addView(sw)

        // ---------- 2. Site files ----------
        root.addView(heading("2. Site files"))
        root.addView(btn("Upload files") { filePicker.launch(arrayOf("*/*")) })
        root.addView(btn("Upload site ZIP (replaces whole site)") { zipPicker.launch(arrayOf("*/*")) })
        root.addView(btn("Clear site") {
            SiteManager.clear(this)
            probe()
        })
        filesView = label("")
        root.addView(filesView)

        // ---------- 3. Termux setup ----------
        root.addView(heading("3. Termux setup (one time)"))
        root.addView(label("Needed for PHP, Python and the public URL. Install Termux from F-Droid first, then do these steps in order."))
        setupView = label("")
        root.addView(setupView)
        root.addView(btn("Step 1: Grant 'Run commands in Termux'") { askTermuxPerm(true) })
        root.addView(btn("Step 2: Copy fix line and open Termux") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PhoneHost", TermuxBridge.ALLOW_LINE))
            toast("Copied. In Termux: long-press, Paste, Enter. Then close Termux fully and open it again.")
            val li = packageManager.getLaunchIntentForPackage("com.termux")
            if (li != null) startActivity(li) else toast("Termux is not installed")
        })
        root.addView(btn("Step 3: Allow Termux 'Display over other apps'") {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:com.termux")))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.termux")))
                } catch (e2: Exception) {
                    toast("Open Android Settings > Apps > Termux > Display over other apps")
                }
            }
        })
        root.addView(btn("Step 4: Install PHP + Python + cloudflared") { termux(TermuxBridge.SETUP_ALL_CMD, false) })

        // ---------- 4. PHP ----------
        root.addView(heading("4. PHP sites"))
        root.addView(label("PHP mode turns on by itself when your site has index.php. PHP runs inside Termux (internal port " + Prefs.PHP_PORT + ")."))
        val phpSw = Switch(this)
        phpSw.text = "PHP mode (force ON)"
        phpSw.isChecked = Prefs.phpMode(this)
        phpSw.setOnCheckedChangeListener { _, checked ->
            Prefs.setPhpMode(this, checked)
            toast(if (checked) "PHP mode ON" else "PHP mode OFF")
        }
        root.addView(phpSw)
        root.addView(btn("Start PHP server (keeps site data)") { startPhp(false) })
        root.addView(btn("Re-sync files and start (overwrites PHP data)") { startPhp(true) })
        root.addView(btn("Stop PHP server") { termux(TermuxBridge.STOP_PHP_CMD, true) })
        root.addView(label("Start keeps data created by your site (settings, uploads). Re-sync replaces everything with the files uploaded here. .htaccess rewrite rules are not supported."))

        // ---------- 5. Public URL ----------
        root.addView(heading("5. Public URL (Cloudflare Tunnel)"))
        val tokenEdit = field("Cloudflare tunnel token (optional, for your own domain)", Prefs.token(this))
        root.addView(tokenEdit)
        root.addView(btn("Start public tunnel") {
            if (!ServerService.running) {
                toast("Start the server first")
            } else {
                Prefs.setToken(this, tokenEdit.text.toString().trim())
                Prefs.setPublicUrl(this, "")
                termux(TermuxBridge.tunnelCmd(Prefs.port(this), Prefs.token(this)), true)
                toast("Tunnel starting. The URL shows in section 1 in 10-20 seconds.")
            }
        })
        root.addView(btn("Stop public tunnel") { termux(TermuxBridge.STOP_TUNNEL_CMD, true) })
        root.addView(label("No token = free random URL (detected automatically). With a token, paste your domain below."))
        val urlEdit = field("Your public URL (https://...)", Prefs.publicUrl(this))
        root.addView(urlEdit)
        root.addView(btn("Save and copy URL") {
            val u = urlEdit.text.toString().trim()
            Prefs.setPublicUrl(this, u)
            if (u.isNotEmpty()) copy(u)
            refresh()
        })

        // ---------- 6. 24x7 ----------
        root.addView(heading("6. 24x7 mode"))
        root.addView(label("Keep the phone charging and turn off battery optimization. On Xiaomi/Oppo/Vivo also enable Autostart and set Battery to No restrictions. Do the same for Termux."))
        root.addView(btn("Turn off battery optimization") { requestIgnoreBattery() })
        root.addView(btn("Open app settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
        })

        // ---------- 7. Python ----------
        root.addView(heading("7. Python / custom Termux command"))
        val cmdEdit = field("Command", Prefs.termuxCmd(this))
        root.addView(cmdEdit)
        root.addView(btn("Run in Termux") {
            Prefs.setTermuxCmd(this, cmdEdit.text.toString())
            termux(cmdEdit.text.toString(), false)
        })

        // ---------- 8. Router ----------
        root.addView(heading("8. Port forwarding (Wi-Fi router)"))
        root.addView(label(routerGuide()))

        // ---------- 9. Update ----------
        root.addView(heading("9. About and updates"))
        root.addView(label("Version: " + BuildConfig.VERSION_NAME))
        updateView = label("")
        root.addView(updateView)
        root.addView(btn("Check for updates") { checkUpdate() })

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun refresh() {
        val port = Prefs.port(this)
        val sb = StringBuilder()
        if (ServerService.running) {
            sb.append("RUNNING\n")
            sb.append("Local: http://localhost:").append(port).append("\n")
            sb.append("Wi-Fi: http://").append(localIp() ?: "?").append(":").append(port).append("\n")
            val th = tunnelHost
            if (th != null) {
                val u = "https://$th"
                if (Prefs.publicUrl(this) != u) Prefs.setPublicUrl(this, u)
            }
            val pu = Prefs.publicUrl(this)
            if (pu.isNotEmpty()) sb.append("Public: ").append(pu).append("\n")
            sb.append("Requests served: ").append(WebServer.requestCount)
            startBtn.text = "Stop"
        } else {
            sb.append("STOPPED\n")
            val err = ServerService.lastError
            sb.append(if (err != null) err else "Tap Start to serve your site.")
            startBtn.text = "Start"
        }
        statusView.text = sb.toString()
        filesView.text = filesText

        val inst = TermuxBridge.isInstalled(this)
        val perm = TermuxBridge.hasPermission(this)
        setupView.text = "Termux: " + (if (inst) "installed" else "NOT installed (get it from F-Droid)") + "\n" +
            "Permission 'Run commands in Termux': " + (if (perm) "granted" else "NOT granted") + "\n" +
            "PHP server: " + (if (phpUp) "running" else "not running")
    }

    /** Slow checks (files, PHP port, tunnel URL) run off the main thread. */
    private fun probe() {
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                phpUp = TermuxBridge.phpUp()
                tunnelHost = TermuxBridge.tunnelHost()
                filesText = SiteManager.list(this)
            } catch (e: Exception) {
                // ignore
            } finally {
                busy.set(false)
            }
            runOnUiThread { if (!isFinishing) refresh() }
        }.start()
    }

    // ================= actions =================
    private fun askTermuxPerm(force: Boolean) {
        if (!TermuxBridge.isInstalled(this)) {
            if (force) toast("Termux is not installed. Install it from F-Droid, then reinstall PhoneHost.")
            return
        }
        if (TermuxBridge.hasPermission(this)) {
            if (force) toast("Already granted")
            return
        }
        if (!force && Prefs.askedTermux(this)) return
        Prefs.setAskedTermux(this, true)
        termuxPerm.launch(TermuxBridge.PERM)
    }

    private fun toggleServer() {
        val i = Intent(this, ServerService::class.java)
        if (ServerService.running) {
            stopService(i)
        } else {
            val p = portEdit.text.toString().toIntOrNull()
            if (p == null || p < 1024 || p > 65535 || p == Prefs.PHP_PORT) {
                toast("Enter a port between 1024 and 65535 (not " + Prefs.PHP_PORT + ", it is used by PHP)")
                return
            }
            Prefs.setPort(this, p)
            ContextCompat.startForegroundService(this, i)
            handler.postDelayed({ autoPhp() }, 1500)
        }
        handler.postDelayed({ refresh() }, 600)
    }

    /** If the site has index.php and PHP is not running, start it automatically. */
    private fun autoPhp() {
        if (!ServerService.running) return
        if (!File(SiteManager.www(this), "index.php").exists()) return
        Thread {
            val up = TermuxBridge.phpUp()
            if (!up) {
                runOnUiThread { startPhp(false) }
            }
        }.start()
    }

    private fun startPhp(overwrite: Boolean) {
        if (!ServerService.running) {
            toast("Start the server first")
            return
        }
        termux(TermuxBridge.phpCmd(Prefs.port(this), Prefs.syncToken(this), Prefs.PHP_PORT, overwrite), true)
        toast("PHP starting in Termux. Wait 5-10 seconds. Keep Termux running.")
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

    private fun termux(cmd: String, background: Boolean) {
        val err = TermuxBridge.run(this, cmd, background)
        if (err != null) toast(err)
    }

    private fun checkUpdate() {
        updateView.text = "Checking..."
        UpdateChecker.check { r ->
            runOnUiThread {
                updateView.text = r.message
                val link = r.downloadUrl
                if (link != null) {
                    val b = AlertDialog.Builder(this)
                    b.setMessage(r.message)
                    b.setPositiveButton("Download and install") { _, _ -> downloadAndInstall(link) }
                    b.setNegativeButton("Later", null)
                    b.show()
                }
            }
        }
    }

    private fun downloadAndInstall(link: String) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow 'Install unknown apps' for PhoneHost, press back, then tap Check for updates again.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
            return
        }
        updateView.text = "Downloading 0%"
        UpdateChecker.download(this, link, { p ->
            runOnUiThread { updateView.text = "Downloading $p%" }
        }) { f, err ->
            runOnUiThread {
                if (f != null) {
                    updateView.text = "Downloaded. Installing..."
                    try {
                        UpdateChecker.install(this, f)
                    } catch (e: Exception) {
                        updateView.text = "Install error: " + e.message
                    }
                } else {
                    updateView.text = "Download failed: " + err
                }
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun full(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun heading(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 22f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setPadding(0, dp(28), 0, dp(8))
        return t
    }

    private fun label(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 15f
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

    private fun btn(labelText: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = labelText
        b.isAllCaps = false
        val lp = full()
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }
}
