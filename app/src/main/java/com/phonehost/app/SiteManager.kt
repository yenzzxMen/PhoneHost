package com.phonehost.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object SiteManager {

    private const val DEFAULT_HTML = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<title>PhoneHost</title></head>" +
        "<body style=\"font-family:sans-serif;text-align:center;padding:40px\">" +
        "<h1>It works!</h1>" +
        "<p>This site is served from your phone.<br>Upload your own files or a ZIP in the PhoneHost app.</p>" +
        "</body></html>"

    fun www(ctx: Context): File {
        val d = File(ctx.filesDir, "www")
        d.mkdirs()
        return d
    }

    fun ensureDefault(ctx: Context) {
        val d = www(ctx)
        val kids = d.listFiles()
        if (kids == null || kids.isEmpty()) {
            File(d, "index.html").writeText(DEFAULT_HTML)
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        val cursor = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor != null) {
            cursor.use {
                if (it.moveToFirst()) {
                    name = it.getString(0)
                }
            }
        }
        return name ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "file")
    }

    fun importFiles(ctx: Context, uris: List<Uri>): Int {
        val dst = www(ctx)
        var n = 0
        for (u in uris) {
            val name = File(displayName(ctx, u)).name
            val ins = ctx.contentResolver.openInputStream(u)
            if (ins != null) {
                ins.use { input ->
                    FileOutputStream(File(dst, name)).use { out -> input.copyTo(out) }
                }
                n++
            }
        }
        return n
    }

    /** Extracts a ZIP and replaces the current site. If the ZIP has a single top folder, it is stripped. */
    fun importZip(ctx: Context, uri: Uri): Int {
        val tmp = File(ctx.cacheDir, "unzip")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpCanon = tmp.canonicalPath
        var n = 0
        val ins = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
        ins.use { raw ->
            ZipInputStream(raw).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(tmp, entry.name).canonicalFile
                    if (!out.path.startsWith(tmpCanon + File.separator)) {
                        throw SecurityException("Bad zip path")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                        n++
                    }
                    entry = zis.nextEntry
                }
            }
        }
        var src = tmp
        val kids = (tmp.listFiles() ?: arrayOf<File>()).filter { it.name != "__MACOSX" }
        if (kids.size == 1 && kids[0].isDirectory) {
            src = kids[0]
        }
        val dst = www(ctx)
        dst.deleteRecursively()
        dst.mkdirs()
        src.copyRecursively(dst, true)
        tmp.deleteRecursively()
        return n
    }

    fun clear(ctx: Context) {
        www(ctx).deleteRecursively()
        ensureDefault(ctx)
    }

    fun list(ctx: Context, max: Int = 25): String {
        val root = www(ctx)
        val all = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList()
        if (all.isEmpty()) return "(empty)"
        val shown = all.take(max).joinToString("\n") { "• $it" }
        return if (all.size > max) shown + "\n... +" + (all.size - max) + " more" else shown
    }
}
