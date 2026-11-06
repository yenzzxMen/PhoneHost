package com.phonehost.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Sends commands to Termux (RUN_COMMAND intent) and checks PHP / tunnel state. */
object TermuxBridge {

    const val PERM = "com.termux.permission.RUN_COMMAND"
    const val METRICS_PORT = 49312

    const val SETUP_ALL_CMD = "pkg update -y && pkg install -y php curl unzip python cloudflared procps"
    const val ALLOW_LINE = "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"
    const val STOP_PHP_CMD = "pkill -x php; echo PHP stopped"
    const val STOP_TUNNEL_CMD = "pkill -x cloudflared; echo Tunnel stopped"

    fun isInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERM) == PackageManager.PERMISSION_GRANTED

    /** Returns null on success, or an error message. background=true: no terminal window, no overlay permission needed. */
    fun run(ctx: Context, script: String, background: Boolean): String? {
        if (!isInstalled(ctx)) return "Termux is not installed (get it from F-Droid)."
        if (!hasPermission(ctx)) return "Permission 'Run commands in Termux' is missing. Use section 3 (Termux setup)."
        return try {
            val i = Intent("com.termux.RUN_COMMAND")
            i.setClassName("com.termux", "com.termux.app.RunCommandService")
            i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            if (!background) {
                i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
            null
        } catch (e: SecurityException) {
            "Permission denied. In Termux set allow-external-apps=true (section 3) and allow the permission."
        } catch (e: Exception) {
            "Termux error: " + e.message
        }
    }

    fun tunnelCmd(port: Int, token: String): String {
        val log = "exec >> ~/phonehost-tunnel.log 2>&1; pkill -x cloudflared; sleep 1; "
        return if (token.isBlank()) {
            log + "cloudflared tunnel --metrics 127.0.0.1:$METRICS_PORT --url http://localhost:$port"
        } else {
            log + "cloudflared tunnel --metrics 127.0.0.1:$METRICS_PORT run --token $token"
        }
    }

    /** Downloads the site from the app (localhost only), unzips it in Termux and starts PHP. */
    fun phpCmd(appPort: Int, token: String, phpPort: Int, overwrite: Boolean): String {
        val flag = if (overwrite) "-o" else "-n"
        return "exec >> ~/phonehost-php.log 2>&1; pkill -x php; sleep 1; " +
            "curl -fs \"http://127.0.0.1:$appPort/__phonehost_sync?token=$token\" -o ~/site.zip" +
            " && mkdir -p ~/phonehost-site" +
            " && unzip $flag -q ~/site.zip -d ~/phonehost-site" +
            " && cd ~/phonehost-site" +
            " && php -S 127.0.0.1:$phpPort"
    }

    /** True if something listens on the PHP port. Call from a background thread. */
    fun phpUp(): Boolean {
        return try {
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", Prefs.PHP_PORT), 400)
                true
            } finally {
                s.close()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Free trycloudflare hostname read from cloudflared's local metrics endpoint, or null. Background thread only. */
    fun tunnelHost(): String? {
        return try {
            val c = URL("http://127.0.0.1:$METRICS_PORT/quicktunnel").openConnection() as HttpURLConnection
            c.connectTimeout = 600
            c.readTimeout = 800
            if (c.responseCode != 200) {
                null
            } else {
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val h = j.optString("hostname", "").trim()
                if (h.isEmpty()) null else h
            }
        } catch (e: Exception) {
            null
        }
    }
}
