package com.phonehost.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Sends commands to Termux. One-time setup inside Termux:
 *   echo "allow-external-apps=true" >> ~/.termux/termux.properties
 * then fully close and reopen Termux.
 */
object TermuxBridge {

    const val SETUP_CMD = "pkg update -y && pkg install -y python cloudflared"
    const val PHP_SETUP_CMD = "pkg update -y && pkg install -y php curl unzip"

    fun isInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Returns null on success, or an error message. */
    fun run(ctx: Context, script: String): String? {
        if (!isInstalled(ctx)) return "Termux is not installed (get it from F-Droid)."
        return try {
            val i = Intent("com.termux.RUN_COMMAND")
            i.setClassName("com.termux", "com.termux.app.RunCommandService")
            i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
            null
        } catch (e: SecurityException) {
            "Permission denied. Set allow-external-apps=true in Termux and allow the permission for this app."
        } catch (e: Exception) {
            "Termux error: " + e.message
        }
    }

    fun tunnelCmd(port: Int, token: String): String {
        return if (token.isBlank()) {
            "cloudflared tunnel --url http://localhost:$port"
        } else {
            "cloudflared tunnel run --token $token"
        }
    }

    /** Downloads the site from the app (localhost only), unzips it in Termux and starts PHP. */
    fun phpCmd(appPort: Int, token: String, phpPort: Int, overwrite: Boolean): String {
        val flag = if (overwrite) "-o" else "-n"
        return "curl -fs \"http://127.0.0.1:$appPort/__phonehost_sync?token=$token\" -o ~/site.zip" +
            " && mkdir -p ~/phonehost-site" +
            " && unzip $flag -q ~/site.zip -d ~/phonehost-site" +
            " && cd ~/phonehost-site" +
            " && php -S 127.0.0.1:$phpPort"
    }
}
