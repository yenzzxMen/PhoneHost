package com.hostpot

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    @Volatile private var last = 0L
    fun check(a: Activity, manual: Boolean) {
        if (Cfg.REPO.startsWith("OWNER")) { if (manual) Toast.makeText(a, "Update repository is not configured", Toast.LENGTH_LONG).show(); return }
        if (!manual && System.currentTimeMillis() - last < 3600_000L) return
        last = System.currentTimeMillis()
        Thread {
            try {
                val c = URL("https://api.github.com/repos/${Cfg.REPO}/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000; c.readTimeout = 8000
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = j.getString("tag_name")
                val arr = j.getJSONArray("assets")
                var url = ""
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    if (x.getString("name").endsWith(".apk")) url = x.getString("browser_download_url")
                }
                @Suppress("DEPRECATION") val cur = a.packageManager.getPackageInfo(a.packageName, 0).versionCode.toLong()
                val remote = tag.substringAfterLast('.').toLongOrNull() ?: 0L
                val notes = j.optString("body", "")
                a.runOnUiThread {
                    if (a.isFinishing) return@runOnUiThread
                    if (remote > cur && url.isNotEmpty()) {
                        AlertDialog.Builder(a).setTitle("UPDATE AVAILABLE")
                            .setMessage("New version: $tag\n(installed build: $cur)\n\n$notes")
                            .setCancelable(false)
                            .setPositiveButton("Update now") { _, _ -> download(a, url) }
                            .setNegativeButton("Later", null).show()
                    } else if (manual) Toast.makeText(a, "Already up to date ($tag)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                if (manual) a.runOnUiThread { Toast.makeText(a, "Update check failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    fun download(a: Activity, url: String) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow 'install unknown apps' for HostPot, then press Update again", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            return
        }
        val ctx = a.applicationContext
        File(a.getExternalFilesDir(null), "update.apk").delete()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url)).setTitle("HostPot update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, "update.apk")
        val id = dm.enqueue(req)
        Toast.makeText(a, "Downloading update...", Toast.LENGTH_SHORT).show()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                val u = dm.getUriForDownloadedFile(id) ?: return
                val inst = Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(inst)
            }
        }
        val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
    }
}
