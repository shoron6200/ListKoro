package com.listkoro.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/** Phone er bhitorei OCR (Google ML Kit) + video theke frame. */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun lines(bmp: Bitmap): List<String> {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS)
        return text.textBlocks.flatMap { b -> b.lines.map { it.text } }
    }

    fun decode(f: File, maxSide: Int = 2000): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun images(files: List<File>): List<String> =
        dedupe(files.flatMap { f -> decode(f)?.let { bmp -> lines(bmp).also { bmp.recycle() } } ?: emptyList() }.let(::clean))

    fun video(f: File, everyMs: Long = 1500, maxFrames: Int = 80): List<String> {
        val r = MediaMetadataRetriever()
        val out = ArrayList<String>()
        try {
            r.setDataSource(f.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var t = 0L
            var n = 0
            while (t <= dur && n < maxFrames) {
                val bmp = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bmp != null) {
                    // choto (360p) frame boro korle OCR bhalo pore
                    val scaled = if (bmp.height < 720) Bitmap.createScaledBitmap(bmp, bmp.width * 720 / bmp.height, 720, true) else bmp
                    out += lines(scaled)
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                }
                t += everyMs; n++
            }
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        return dedupe(clean(out))
    }

    fun clean(lines: List<String>): List<String> = lines.mapNotNull { raw ->
        val line = raw.replace(Regex("\\s+"), " ").trim(' ', '|', '_', '~', '-', '—', '=', '•', '*', '·', '.', ',', ':', ';', '\'', '"', '`')
        val letters = line.count { it.isLetterOrDigit() }
        val hasWord = line.split(" ").any { w -> w.count { it.isLetter() } >= 3 }
        if (hasWord && letters.toDouble() / maxOf(line.length, 1) > 0.5) line else null
    }

    fun dedupe(lines: List<String>, threshold: Double = 0.85): List<String> {
        val kept = ArrayList<String>()
        val norms = ArrayList<String>()
        for (l in lines) {
            val n = l.lowercase().filter { it.isLetterOrDigit() }
            if (n.isEmpty()) continue
            if (norms.takeLast(60).any { it == n || similarity(it, n) >= threshold }) continue
            kept += l; norms += n
        }
        return kept
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
