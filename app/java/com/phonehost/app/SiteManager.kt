package com.phonehost.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object SiteManager {

    private const val DEFAULT_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PhoneHost</title></head>
<body style="font-family:sans-serif;text-align:center;padding:40px">
<h1>It works! 🎉</h1>
<p>Ye site aapke phone se serve ho rahi hai.<br>PhoneHost app me apni files ya ZIP upload karo.</p>
</body></html>"""

    fun www(ctx: Context): File = File(ctx.filesDir, "www").also { it.mkdirs() }

    fun ensureDefault(ctx: Context) {
        val d = www(ctx)
        if (d.listFiles().isNullOrEmpty()) File(d, "index.html").writeText(DEFAULT_HTML)
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun importFiles(ctx: Context, uris: List<Uri>): Int {
        val dst = www(ctx)
        var n = 0
        for (u in uris) {
            val name = File(displayName(ctx, u)).name
            ctx.contentResolver.openInputStream(u)?.use { ins ->
                FileOutputStream(File(dst, name)).use { ins.copyTo(it) }
                n++
            }
        }
        return n
    }

    /** ZIP extract karke purani site replace karta hai. Single top folder ho to usse hata deta hai. */
    fun importZip(ctx: Context, uri: Uri): Int {
        val tmp = File(ctx.cacheDir, "unzip").apply { deleteRecursively(); mkdirs() }
        val tmpCanon = tmp.canonicalPath
        var n = 0
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            ZipInputStream(ins).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val out = File(tmp, e.name).canonicalFile
                    if (!out.path.startsWith(tmpCanon + File.separator)) throw SecurityException("Bad zip path")
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                        n++
                    }
                    e = zis.nextEntry
                }
            }
        }
        var src = tmp
        val kids = tmp.listFiles()?.filter { it.name != "__MACOSX" } ?: emptyList()
        if (kids.size == 1 && kids[0].isDirectory) src = kids[0]
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
        return if (all.size > max) "$shown\n… +${all.size - max} more" else shown
    }
}
