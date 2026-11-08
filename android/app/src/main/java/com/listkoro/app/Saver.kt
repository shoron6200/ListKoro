package com.listkoro.app

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Download kora file phone er Movies/ListKoro (video) ba Music/ListKoro (mp3) te rakhe, Gallery/Music app e dekhabe. */
object Saver {
    class Saved(val uri: String, val name: String, val location: String, val size: Long, val mime: String)

    private fun clean(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
        .ifBlank { "video" }

    fun save(file: File, kind: String, title: String): Saved {
        val audio = kind == "audio" || file.extension.equals("mp3", true)
        val ext = file.extension.ifBlank { if (audio) "mp3" else "mp4" }
        val mime = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "webm" -> if (audio) "audio/webm" else "video/webm"
            "mkv" -> "video/x-matroska"; else -> if (audio) "audio/mpeg" else "video/mp4"
        }
        val name = "${clean(title)}.$ext"
        val dir = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val ctx = Store.ctx
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                             else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/ListKoro")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(collection, values) ?: throw FetchError("Phone e file save kora gelo na")
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw FetchError("Phone e file save kora gelo na")
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                ctx.contentResolver.delete(uri, null, null)
                throw e
            }
            return Saved(uri.toString(), name, "$dir/ListKoro", file.length(), mime)
        }
        // Android 9 ba niche: public folder + media scan (WRITE_EXTERNAL_STORAGE lage)
        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(dir), "ListKoro").apply { mkdirs() }
        var dest = File(folder, name)
        var n = 1
        while (dest.exists()) dest = File(folder, "${clean(title)} ($n).$ext").also { n++ }
        file.copyTo(dest)
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mime)) { _, u -> scanned = u; latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        return Saved((scanned ?: Uri.fromFile(dest)).toString(), dest.name, "$dir/ListKoro", dest.length(), mime)
    }

    /** Phone theke file muche fela. Nijer banano file na hole (reinstall er por) false. */
    fun delete(uri: String): Boolean = try {
        val u = Uri.parse(uri)
        if (u.scheme == "file") File(u.path ?: "").delete() else Store.ctx.contentResolver.delete(u, null, null) > 0
    } catch (_: Exception) {
        false
    }
}
