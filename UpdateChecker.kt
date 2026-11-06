package com.phonehost.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Releases se latest version check karta hai. */
object UpdateChecker {

    data class Result(val message: String, val downloadUrl: String?)

    fun check(onDone: (Result) -> Unit) {
        Thread {
            val res = try {
                val c = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode != 200) {
                    Result("Release nahi mila (HTTP ${c.responseCode}). Repo public hai aur release bana hai?", null)
                } else {
                    val j = JSONObject(c.inputStream.bufferedReader().readText())
                    val tag = j.getString("tag_name")
                    val assets = j.getJSONArray("assets")
                    var apk: String? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
                    }
                    if (isNewer(tag, BuildConfig.VERSION_NAME) && apk != null)
                        Result("Naya version mila: $tag (current ${BuildConfig.VERSION_NAME})", apk)
                    else
                        Result("Aap latest version par ho (${BuildConfig.VERSION_NAME}).", null)
                }
            } catch (e: Exception) {
                Result("Update check fail: ${e.message}", null)
            }
            onDone(res)
        }.start()
    }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
