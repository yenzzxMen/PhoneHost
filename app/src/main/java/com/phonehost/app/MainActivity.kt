package com.phonehost.app

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
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

    private lateinit var status: TextView
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

    // ---------- UI ----------
    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(32))

        root.addView(heading("PhoneHost"))
        root.addView(label("Host your own website from your phone."))

        // 1. Server
        root.addView(section("1. Server"))
        status = label("")
        root.addView(status)
        portEdit = field("Port (1024-65535)", Prefs.port(this).toString())
        portEdit.inputType = InputType.TYPE_CLASS_NUMBER
        root.addView(portEdit)
        startBtn = action("Start") { toggleServer() }
        root.addView(startBtn)
        root.addView(action("Open site in browser") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:" + Prefs.port(this))))
        })
        val sw = Switch(this)
        sw.text = "Auto-start after phone reboot"
        sw.isChecked = Prefs.autoStart(this)
        sw.setOnCheckedChangeListener { _, checked -> Prefs.setAutoStart(this, checked) }
        root.addView(sw)

        // 2. Site files
        root.addView(section("2. Site files"))
        root.addView(action("Upload files") { filePicker.launch(arrayOf("*/*")) })
        root.addView(action("Upload site ZIP (replaces whole site)") { zipPicker.launch(arrayOf("*/*")) })
        root.addView(action("Clear site") {
            SiteManager.clear(this)
            refresh()
        })
        filesView = label("")
        root.addView(filesView)

        // 3. 24x7
        root.addView(section("3. 24x7 mode"))
        root.addView(label("Keep the phone charging and turn off battery optimization. On Xiaomi/Oppo/Vivo also enable Autostart and set Battery to No restrictions."))
        root.addView(action("Turn off battery optimization") { requestIgnoreBattery() })
        root.addView(action("Open app settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
        })

        // 4. Public URL
        root.addView(section("4. Public URL (Cloudflare Tunnel via Termux)"))
        root.addView(label(
            "First-time setup:\n" +
            "a) Install Termux from F-Droid.\n" +
            "b) In Termux run:\n   echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
            "c) Close Termux fully and reopen it. Then in Android Settings > Apps > PhoneHost > Permissions, allow 'Run commands in Termux'.\n" +
            "d) Tap 'Termux setup' below."
        ))
        root.addView(action("Termux setup (python + cloudflared)") { termux(TermuxBridge.SETUP_CMD) })
        val tokenEdit = field("Cloudflare tunnel token (optional, for your own domain)", Prefs.token(this))
        root.addView(tokenEdit)
        root.addView(action("Start public tunnel") {
            Prefs.setToken(this, tokenEdit.text.toString().trim())
            termux(TermuxBridge.tunnelCmd(Prefs.port(this), Prefs.token(this)))
            toast("The trycloudflare.com URL will appear in Termux")
        })
        root.addView(label("No token = free random URL (https://xyz.trycloudflare.com). With a token = your own domain (get it from Cloudflare Zero Trust > Tunnels)."))

        // 5. Python
        root.addView(section("5. Python / Termux command"))
        val cmdEdit = field("Command", Prefs.termuxCmd(this))
        root.addView(cmdEdit)
        root.addView(action("Run in Termux") {
            Prefs.setTermuxCmd(this, cmdEdit.text.toString())
            termux(cmdEdit.text.toString())
        })

        // 6. Router guide
        root.addView(section("6. Port forwarding (Wi-Fi router)"))
        root.addView(label(routerGuide()))

        // 7. Update
        root.addView(section("7. Update"))
        root.addView(label("Version: " + BuildConfig.VERSION_NAME))
        root.addView(action("Check for updates") { checkUpdate() })

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun refresh() {
        val ip = localIp()
        val port = Prefs.port(this)
        if (ServerService.running) {
            status.text = "RUNNING\nLocal: http://localhost:" + port +
                "\nWi-Fi: http://" + (ip ?: "?") + ":" + port +
                "\nRequests served: " + WebServer.requestCount
            startBtn.text = "Stop"
        } else {
            val err = ServerService.lastError
            status.text = "STOPPED" + (if (err != null) "\n" + err else "")
            startBtn.text = "Start"
        }
        filesView.text = SiteManager.list(this)
    }

    // ---------- actions ----------
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

    // ---------- helpers ----------
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
        t.textSize = 28f
        t.setPadding(0, 0, 0, dp(4))
        return t
    }

    private fun section(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 18f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setPadding(0, dp(24), 0, dp(8))
        return t
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

    private fun action(labelText: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = labelText
        b.isAllCaps = false
        b.layoutParams = full()
        b.setOnClickListener { onClick() }
        return b
    }
}
