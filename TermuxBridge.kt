package com.phonehost.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Termux ko command bhejta hai. Termux me ek baar ye karna zaroori hai:
 *   echo "allow-external-apps=true" >> ~/.termux/termux.properties
 * phir Termux fully close karke dobara kholo, aur Android me
 * PhoneHost ko "Run commands in Termux" permission do.
 */
object TermuxBridge {

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo("com.termux", 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    /** returns null on success, error message otherwise */
    fun run(ctx: Context, script: String): String? {
        if (!isInstalled(ctx)) return "Termux installed nahi hai (F-Droid se install karo)."
        return try {
            val i = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            null
        } catch (e: SecurityException) {
            "Permission nahi mili. Termux me allow-external-apps=true set karo aur app permission do."
        } catch (e: Exception) {
            "Termux error: ${e.message}"
        }
    }

    const val SETUP_CMD = "pkg update -y && pkg install -y python cloudflared"

    fun tunnelCmd(port: Int, token: String): String =
        if (token.isBlank()) "cloudflared tunnel --url http://localhost:$port"
        else "cloudflared tunnel run --token $token"
}
