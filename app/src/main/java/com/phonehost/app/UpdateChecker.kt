package com.phonehost.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the latest version on GitHub Releases. */
object UpdateChecker {

    class Result(val message: String, val downloadUrl: String?)

    fun check(onDone: (Result) -> Unit) {
        Thread {
            val res: Result = try {
                val url = URL("https://api.github.com/repos/" + BuildConfig.GITHUB_REPO + "/releases/latest")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode != 200) {
                    Result("No release found (HTTP " + c.responseCode + "). Is the repo public and a release published?", null)
                } else {
                    val j = JSONObject(c.inputStream.bufferedReader().readText())
                    val tag = j.getString("tag_name")
                    val assets = j.getJSONArray("assets")
                    var apk: String? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) {
                            apk = a.getString("browser_download_url")
                        }
                    }
                    if (apk != null && isNewer(tag, BuildConfig.VERSION_NAME)) {
                        Result("New version available: " + tag + " (current " + BuildConfig.VERSION_NAME + ")", apk)
                    } else {
                        Result("You are on the latest version (" + BuildConfig.VERSION_NAME + ").", null)
                    }
                }
            } catch (e: Exception) {
                Result("Update check failed: " + e.message, null)
            }
            onDone(res)
        }.start()
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
