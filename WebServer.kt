package com.phonehost.app

import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class WebServer(private val root: File, port: Int) : NanoHTTPD(port) {

    companion object {
        @Volatile var requestCount = 0L
    }

    private val rootCanon = root.canonicalFile

    override fun serve(session: IHTTPSession): Response {
        requestCount++
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "405 Method Not Allowed")
        }
        val path = session.uri.substringBefore('?')
        var file = File(rootCanon, path).canonicalFile
        if (file != rootCanon && !file.path.startsWith(rootCanon.path + File.separator)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "403 Forbidden")
        }
        if (file.isDirectory) file = File(file, "index.html")
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
}
