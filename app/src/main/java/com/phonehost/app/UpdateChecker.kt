package com.phonehost.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases, downloads the APK inside the app and starts the install. */
object UpdateChecker {

    class Result(val message: String, val downloadUrl: String?)

    fun check(onDone: (Result) -> Unit) {
        Thread {
            val res: Result = try {
                val url = URL("https://api.github.com/repos/" + BuildConfig.GITHUB_REPO + "/releases?per_page=5")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "PhoneHost")
                if (c.responseCode != 200) {
                    Result("No release found (HTTP " + c.responseCode + "). Repo must be public and have a release.", null)
                } else {
                    val arr = JSONArray(c.inputStream.bufferedReader().readText())
                    var tag: String? = null
                    var apk: String? = null
                    var idx = 0
                    while (idx < arr.length() && apk == null) {
                        val j = arr.getJSONObject(idx)
                        idx++
                        if (j.optBoolean("draft", false)) continue
                        val assets = j.getJSONArray("assets")
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.getString("name").endsWith(".apk")) {
                                apk = a.getString("browser_download_url")
                                tag = j.getString("tag_name")
                            }
                        }
                    }
                    if (apk != null && tag != null && isNewer(tag, BuildConfig.VERSION_NAME)) {
                        Result("New version available: " + tag + " (current " + BuildConfig.VERSION_NAME + ")", apk)
                    } else {
                        Result("You are on the latest version (" + BuildConfig.VERSION_NAME + ").", null)
                    }
                }
            } catch (e: Exception) {
                Result("Update check failed: " + (e.message ?: e.javaClass.simpleName), null)
            }
            onDone(res)
        }.start()
    }

    /** Downloads the APK to the app cache. onProgress gets 0..100. onDone gets (file, null) or (null, error). */
    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit, onDone: (File?, String?) -> Unit) {
        Thread {
            var outFile: File? = null
            var err: String? = null
            try {
                val dir = File(ctx.cacheDir, "updates")
                dir.deleteRecursively()
                dir.mkdirs()
                val f = File(dir, "PhoneHost.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "PhoneHost")
                if (c.responseCode != 200) {
                    err = "HTTP " + c.responseCode
                } else {
                    val total = c.contentLength.toLong()
                    var done = 0L
                    var last = -1
                    c.inputStream.use { ins ->
                        FileOutputStream(f).use { out ->
                            val buf = ByteArray(16384)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = (done * 100 / total).toInt()
                                    if (p != last) {
                                        last = p
                                        onProgress(p)
                                    }
                                }
                            }
                        }
                    }
                    outFile = f
                }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            onDone(outFile, err)
        }.start()
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun parts(v: String): List<Int> {
        return v.trim().removePrefix("v").split('.').map { p ->
            p.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote)
        val l = parts(local)
        val size = maxOf(r.size, l.size)
        for (i in 0 until size) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
