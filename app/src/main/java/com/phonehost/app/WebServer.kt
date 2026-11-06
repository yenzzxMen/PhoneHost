package com.phonehost.app

import android.content.Context
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebServer(root: File, port: Int, private val appCtx: Context) : NanoHTTPD(port) {

    companion object {
        @Volatile
        var requestCount = 0L
    }

    private class CodeStatus(private val code: Int, private val desc: String) : Response.IStatus {
        override fun getRequestStatus(): Int = code
        override fun getDescription(): String = "$code $desc"
    }

    private val rootCanon: File = root.canonicalFile

    /** PHP mode is on if the switch is ON or the site has an index.php. */
    private fun needsPhp(): Boolean =
        Prefs.phpMode(appCtx) || File(rootCanon, "index.php").exists()

    override fun serve(session: IHTTPSession): Response {
        requestCount++
        val path = session.uri.substringBefore('?')

        if (path == "/__phonehost_sync") return sync(session)

        if (path.split('/').any { it.length > 1 && it.startsWith(".") }) return forbidden()
        if (denied(path)) return forbidden()

        if (needsPhp()) return proxy(session)

        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "405 Method Not Allowed")
        }
        var file = File(rootCanon, path).canonicalFile
        if (file != rootCanon && !file.path.startsWith(rootCanon.path + File.separator)) {
            return forbidden()
        }
        if (file.isDirectory) {
            file = File(file, "index.html")
        }
        if (file.extension.lowercase() == "php") {
            return newFixedLengthResponse(
                Response.Status.NOT_IMPLEMENTED, "text/html",
                "<h2>This site needs PHP</h2><p>Open PhoneHost, go to PHP section, turn on PHP mode and start the PHP server.</p>"
            )
        }
        if (!file.exists() || !file.isFile) {
            val nf = File(rootCanon, "404.html")
            return if (nf.exists()) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html", nf.readText())
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
            }
        }
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "wasm" -> "application/wasm"
            "svg" -> "image/svg+xml"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
    }

    private fun forbidden(): Response =
        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "403 Forbidden")

    /** Emulates ".htaccess: deny from all" for sub folders (e.g. data/). */
    private fun denied(path: String): Boolean {
        var d: File? = File(rootCanon, path).canonicalFile
        if (d != null && !d.isDirectory) d = d.parentFile
        while (d != null && d.path.startsWith(rootCanon.path)) {
            if (d != rootCanon) {
                val h = File(d, ".htaccess")
                if (h.isFile) {
                    val t = h.readText().lowercase()
                    if (t.contains("require all denied") || t.contains("deny from all")) return true
                }
            }
            if (d == rootCanon) break
            d = d.parentFile
        }
        return false
    }

    /** Lets Termux download the site as a ZIP. Only from localhost and with the secret token. */
    private fun sync(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress ?: ""
        val local = ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1"
        val tok = session.parms["token"]
        if (!local || tok == null || tok != Prefs.syncToken(appCtx)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
        }
        val tmp = File(appCtx.cacheDir, "site-sync.zip")
        ZipOutputStream(FileOutputStream(tmp)).use { zos ->
            rootCanon.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(rootCanon).path.replace(File.separatorChar, '/')))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/zip", FileInputStream(tmp), tmp.length())
    }

    /** Adds every Set-Cookie header (NanoHTTPD keeps only one per name with addHeader). */
    private fun addCookie(resp: Response, value: String) {
        try {
            val m = resp.javaClass.getMethod("addCookieHeader", String::class.java)
            m.invoke(resp, value)
        } catch (e: Exception) {
            resp.addHeader("Set-Cookie", value)
        }
    }

    /** Forwards the request to the PHP server running in Termux. */
    private fun proxy(session: IHTTPSession): Response {
        try {
            val q = session.queryParameterString
            val full = "http://127.0.0.1:" + Prefs.PHP_PORT + session.uri + (if (q != null && q.isNotEmpty()) "?$q" else "")
            val c = URL(full).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 3000
            c.readTimeout = 30000
            c.requestMethod = session.method.name
            for ((k, v) in session.headers) {
                if (k == "host" || k == "connection" || k == "content-length" || k == "accept-encoding") continue
                if (k == "remote-addr" || k == "http-client-ip") continue
                c.setRequestProperty(k, v)
            }
            val host = session.headers["host"]
            if (host != null) c.setRequestProperty("X-Forwarded-Host", host)
            c.setRequestProperty("X-Forwarded-Proto", session.headers["x-forwarded-proto"] ?: "http")

            val len = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 0 && (session.method == Method.POST || session.method == Method.PUT)) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(len)
                val buf = ByteArray(8192)
                var left = len
                c.outputStream.use { out ->
                    while (left > 0) {
                        val n = session.inputStream.read(buf, 0, minOf(buf.size, left))
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val ctype = c.contentType ?: "text/html"
            val status = CodeStatus(code, c.responseMessage ?: "")
            val resp = if (stream != null) {
                newChunkedResponse(status, ctype, stream)
            } else {
                newFixedLengthResponse(status, ctype, "")
            }
            for ((k, vs) in c.headerFields) {
                if (k == null) continue
                val lk = k.lowercase()
                if (lk == "content-length" || lk == "transfer-encoding" || lk == "content-type" || lk == "connection") continue
                if (lk == "set-cookie") {
                    for (v in vs) addCookie(resp, v)
                    continue
                }
                if (vs.isNotEmpty()) resp.addHeader(k, vs[vs.size - 1])
            }
            return resp
        } catch (e: Exception) {
            return newFixedLengthResponse(
                CodeStatus(502, "Bad Gateway"), "text/html",
                "<h2>PHP server is not running</h2>" +
                    "<p>Open PhoneHost and tap Start PHP server (section 4). Keep Termux running.</p>" +
                    "<p>Details: " + e.javaClass.simpleName + ": " + e.message + "</p>"
            )
        }
    }
}
