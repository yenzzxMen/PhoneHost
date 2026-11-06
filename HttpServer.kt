package com.hostpot

import android.net.Uri
import java.io.*
import java.net.*
import java.util.concurrent.*

class Rules {
    var index: List<String> = listOf("index.html", "index.htm", "index.php")
    val redirects = HashMap<String, String>()
    var err404: String? = null
    var fallback: String? = null   // SPA (React/Vue): FallbackResource /index.html
    var proxyAll = false           // ProxyAll on  -> everything goes to the Termux backend
}

object Htaccess {
    fun load(dir: File): Rules {
        val r = Rules()
        val f = listOf(File(dir, ".htaccess"), File(dir, "htaccess/.htaccess"), File(dir, "htaccess/rules.txt"))
            .firstOrNull { it.isFile } ?: return r
        for (l in f.readLines()) {
            val p = l.trim().split(Regex("\\s+"))
            if (p.isEmpty() || p[0].startsWith("#")) continue
            when (p[0].lowercase()) {
                "directoryindex" -> { if (p.size > 1) r.index = p.drop(1) }
                "redirect" -> {
                    val a = p.drop(1).filter { it.startsWith("/") || it.startsWith("http") }
                    if (a.size >= 2) r.redirects[a[0]] = a[1]
                }
                "fallbackresource" -> { if (p.size > 1) r.fallback = p[1] }
                "proxyall" -> { r.proxyAll = p.size > 1 && p[1].lowercase() in listOf("on", "true", "1") }
                "errordocument" -> { if (p.size >= 3 && p[1] == "404") r.err404 = p[2] }
            }
        }
        return r
    }
}

class HttpServer(private val port: Int) {
    @Volatile var running = false
    private var ss: ServerSocket? = null
    private var pool: ExecutorService? = null
    val alive: Boolean get() = running && ss?.isClosed == false

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        ss = s
        pool = Executors.newCachedThreadPool()
        running = true
        Thread({
            while (running) {
                try {
                    val c = s.accept()
                    pool?.execute {
                        try { handle(c) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} }
                    }
                } catch (e: Exception) {
                    if (running) Logx.add("accept error: " + e.message)
                    break
                }
            }
        }, "accept").start()
        Logx.add("server started on :$port")
    }

    fun stop() {
        running = false
        try { ss?.close() } catch (_: Exception) {}
        pool?.shutdownNow()
        Logx.add("server stopped")
    }

    private fun readHead(i: InputStream): String? {
        val sb = ByteArrayOutputStream()
        var m = 0
        while (sb.size() < 16384) {
            val b = i.read()
            if (b < 0) return null
            sb.write(b)
            m = if ((b == 13 && (m == 0 || m == 2)) || (b == 10 && (m == 1 || m == 3))) m + 1 else if (b == 13) 1 else 0
            if (m == 4) break
        }
        return String(sb.toByteArray(), Charsets.ISO_8859_1).trimEnd()
    }

    private fun writeHead(o: OutputStream, code: Int, msg: String, len: Long, h: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $msg\r\nServer: HostPot/95\r\nConnection: close\r\nContent-Length: $len\r\n")
        for ((k, v) in h) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        o.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun send(o: OutputStream, code: Int, msg: String, type: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        writeHead(o, code, msg, body.size.toLong(), mapOf("Content-Type" to "$type; charset=utf-8") + extra)
        o.write(body); o.flush()
        Stats.bytes.addAndGet(body.size.toLong())
    }

    private fun handle(c: Socket) {
        c.soTimeout = 20000
        val inp = BufferedInputStream(c.getInputStream())
        val out = BufferedOutputStream(c.getOutputStream())
        val head = readHead(inp) ?: return
        val lines = head.split("\r\n")
        val rl = lines[0].split(" ")
        if (rl.size < 2) return
        val method = rl[0]; val target = rl[1]
        val hd = HashMap<String, String>()
        for (l in lines.drop(1)) { val k = l.indexOf(':'); if (k > 0) hd[l.substring(0, k).trim().lowercase()] = l.substring(k + 1).trim() }
        Stats.req.incrementAndGet()
        val ip = c.inetAddress.hostAddress ?: "?"
        var path = Uri.decode(target.substringBefore("?"))
        var fwdTarget = target
        var site = Cfg.siteForHost(hd["host"]?.substringBefore(":")) ?: Cfg.active
        if (path.startsWith("/s/")) {
            val rest = path.substring(3)
            val n = rest.substringBefore("/")
            if (!rest.contains("/") && Cfg.sites().contains(n)) {
                send(out, 301, "Moved Permanently", "text/plain", ByteArray(0), mapOf("Location" to "/s/$n/")); return
            }
            if (Cfg.sites().contains(n)) { site = n; path = "/" + rest.substringAfter("/", ""); fwdTarget = target.substring(3 + n.length) }
        }
        Logx.add("$ip $method $target [$site]")
        Stats.hit(site)
        val dir = Cfg.siteDir(site)
        val rules = Htaccess.load(dir)
        rules.redirects[path]?.let { send(out, 302, "Found", "text/plain", ByteArray(0), mapOf("Location" to it)); return }
        val segs = path.split("/")
        if (path.contains("..") || segs.any { it.startsWith(".") } || segs.contains("htaccess")) {
            send(out, 403, "Forbidden", "text/html", "<h1>403 Forbidden</h1>".toByteArray()); return
        }
        val nodeOrPy = (Cfg.backend == "node" && File(dir, "server.js").isFile) || (Cfg.backend == "python" && File(dir, "app.py").isFile)
        val be = Cfg.backend != "none"
        if (rules.proxyAll || nodeOrPy || path.endsWith(".php") || (be && (path.endsWith(".py") || path.startsWith("/api/")))) {
            proxy(head, inp, out, hd, ip, site, fwdTarget); return
        }
        val base = File(dir, path)
        var f: File? = base
        if (base.isDirectory) {
            if (!path.endsWith("/")) {
                val q = target.substringAfter("?", "")
                val loc = target.substringBefore("?") + "/" + (if (q.isEmpty()) "" else "?$q")
                send(out, 301, "Moved Permanently", "text/plain", ByteArray(0), mapOf("Location" to loc)); return
            }
            f = rules.index.map { File(base, it) }.firstOrNull { it.isFile }
            if (f != null && f.name.endsWith(".php")) { proxy(head, inp, out, hd, ip, site, fwdTarget); return }
        }
        val okPath = f != null && f.isFile && f.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        if (!okPath && rules.fallback != null && !path.substringAfterLast('/').contains('.')) {
            val fb = File(dir, rules.fallback!!.trimStart('/'))
            if (fb.isFile) { f = fb }
        }
        if (f == null || !f.isFile || !f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            val ed = rules.err404?.let { File(dir, it.trimStart('/')) }
            val body = if (ed != null && ed.isFile) ed.readBytes() else "<h1>404 Not Found</h1><hr>HostPot".toByteArray()
            send(out, 404, "Not Found", "text/html", body); return
        }
        // Range support (video seek, resume download)
        val total = f.length()
        var from = 0L; var to = total - 1; var partial = false
        val rg = hd["range"]
        if (rg != null && rg.startsWith("bytes=") && total > 0) {
            val sp = rg.substring(6).substringBefore(",").split("-")
            val a1 = sp.getOrNull(0)?.trim()?.toLongOrNull()
            val b1 = sp.getOrNull(1)?.trim()?.toLongOrNull()
            if (a1 != null) { from = a1; if (b1 != null) to = minOf(b1, total - 1) }
            else if (b1 != null) { from = maxOf(0L, total - b1) }
            if (from > to || from >= total) {
                writeHead(out, 416, "Range Not Satisfiable", 0L, mapOf("Content-Range" to "bytes */$total")); out.flush(); return
            }
            partial = true
        }
        val len = to - from + 1
        val hh = HashMap<String, String>()
        hh["Content-Type"] = mime(f.name); hh["Cache-Control"] = "no-cache"; hh["Accept-Ranges"] = "bytes"
        if (partial) hh["Content-Range"] = "bytes $from-$to/$total"
        writeHead(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", if (total == 0L) 0L else len, hh)
        if (method != "HEAD" && total > 0) {
            RandomAccessFile(f, "r").use { r ->
                r.seek(from)
                val buf = ByteArray(16384)
                var left = len
                while (left > 0) {
                    val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n); left -= n
                    Stats.bytes.addAndGet(n.toLong())
                }
            }
        }
        out.flush()
    }

    private fun proxy(head0: String, inp: InputStream, out: OutputStream, hd: Map<String, String>, ip: String, site: String, fwdTarget: String) {
        val bp = Cfg.backendPort
        // the backend (php/node/python) runs inside the ACTIVE site folder and knows nothing about /s/<name>/
        val rl = head0.substringBefore("\r\n").split(" ")
        val head = (listOf(rl[0], fwdTarget) + rl.drop(2)).joinToString(" ") + "\r\n" + head0.substringAfter("\r\n", "")
        if (site != Cfg.active) {
            send(out, 502, "Bad Gateway", "text/html",
                ("<h1>Site '$site' needs a backend</h1>The backend only serves the ACTIVE site (now: '${Cfg.active}').<br>" +
                 "Open HostPot > SITES > ACTIVE for '$site', then TERMUX > START PHP / NODE / PYTHON.").toByteArray()); return
        }
        try {
            Socket("127.0.0.1", bp).use { b ->
                b.soTimeout = 60000
                val bo = b.getOutputStream()
                val h = head.split("\r\n").filter { !it.lowercase().startsWith("connection:") }
                    .joinToString("\r\n") + "\r\nConnection: close\r\nX-Forwarded-For: $ip\r\n\r\n"
                bo.write(h.toByteArray(Charsets.ISO_8859_1))
                var n = hd["content-length"]?.toLongOrNull() ?: 0L
                val buf = ByteArray(8192)
                while (n > 0) {
                    val r = inp.read(buf, 0, minOf(buf.size.toLong(), n).toInt())
                    if (r < 0) break
                    bo.write(buf, 0, r); n -= r
                }
                bo.flush()
                val bi = b.getInputStream()
                while (true) {
                    val r = bi.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    Stats.bytes.addAndGet(r.toLong())
                }
                out.flush()
            }
        } catch (e: Exception) {
            send(out, 502, "Bad Gateway", "text/html",
                ("<h1>502 Bad Gateway</h1>Backend is not running on port $bp.<br>" +
                 "This site needs PHP / Node / Python. Open HostPot > TERMUX > INSTALL PACKAGES (once), then START PHP / NODE / PYTHON.<br>" +
                 "Plain HTML sites do not need this: remove index.php / ProxyAll or upload index.html.").toByteArray())
        }
    }

    private fun mime(n: String): String {
        val t = when (n.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "txt", "md", "log" -> "text/plain; charset=utf-8"
            "xml" -> "application/xml"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "wasm" -> "application/wasm"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
        return t
    }
}
