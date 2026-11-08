package com.listkoro.app

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

class FetchError(msg: String) : Exception(msg)

class Media(val kind: String, val files: List<File>, val caption: String, val title: String,
            val audioOnly: Boolean = false, var warning: String = "")

/** Link theke media: yt-dlp (video/reel) + og:image fallback (photo/poster post). */
object Fetcher {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val MAX_VIDEO_SEC = 20 * 60
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val URL_RE = Regex("https?://[^\\s<>\"']+")
    // Ei link gula video: fail hole poster/thumbnail e chole jabo na
    private val VIDEO_RE = Regex(
        "facebook\\.com/(reel|reels|watch|share/[vr])\\b|facebook\\.com/.+/videos?/|fb\\.watch/|" +
            "instagram\\.com/(reel|reels|tv)/|youtube\\.com/(watch|shorts)|youtu\\.be/|tiktok\\.com/",
        RegexOption.IGNORE_CASE)
    private val SHORT_RE = Regex("facebook\\.com/share/|fb\\.watch/|fb\\.me/|l\\.facebook\\.com/", RegexOption.IGNORE_CASE)

    fun isVideoUrl(url: String) = VIDEO_RE.containsMatchIn(url)

    private fun desktop(url: String) = url.replace(Regex("://(m|mbasic|web|touch)\\.facebook\\.com"), "://www.facebook.com")

    /** FB share/short link -> asol post URL (yt-dlp er jonno). */
    fun resolveUrl(url: String): String {
        val u = desktop(url)
        if (!SHORT_RE.containsMatchIn(u)) return u
        return try {
            val host = URI(u).host ?: ""
            val rb = Request.Builder().url(u).header("User-Agent", DESKTOP_UA).header("Accept-Language", "en")
            cookieHeader(host)?.let { rb.header("Cookie", it) }
            val landed = Ai.http.newCall(rb.build()).execute().use { it.request.url.toString() }
            if (landed.contains("login") || landed.contains("checkpoint")) u else desktop(landed)
        } catch (_: Exception) {
            u
        }
    }

    private fun loginHint(url: String): String {
        val site = when {
            url.contains("instagram.com") -> "Instagram"
            url.contains("facebook.com") || url.contains("fb.watch") || url.contains("fb.me") -> "Facebook"
            else -> return " Link ta public kina check koro."
        }
        return if (Store.cookies.exists()) " Cookies expire hoye thakte pare: Settings → $site e login abar koro."
               else " Private/login-only post hote pare: Settings → $site e login koro."
    }

    fun findUrl(text: String?): String? = text?.let { URL_RE.find(it)?.value?.trimEnd(')', '.', ',') }

    private fun shortErr(e: Throwable): String {
        val m = (e.message ?: e.toString()).replace(Regex("\u001b\\[[0-9;]*m"), "")
        val line = m.lines().lastOrNull { it.contains("ERROR") } ?: m.lines().lastOrNull { it.isNotBlank() } ?: m
        return line.substringBefore("; please report").replace("ERROR: ", "").trim().take(220)
    }

    private fun request(url: String): YoutubeDLRequest = YoutubeDLRequest(url).apply {
        addOption("--no-warnings")
        // User-Agent dei na: yt-dlp nijer desktop UA dey, FB extractor sei page e kaj kore
        addOption("--socket-timeout", "30")
        if (Store.cookies.exists()) addOption("--cookies", Store.cookies.absolutePath)
    }

    private fun caption(info: JSONObject): String =
        listOf(info.optString("title"), info.optString("description"))
            .map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.distinct().joinToString("\n").take(6000)

    /** yt-dlp ready kora + update er sathe clash na howa (read lock) */
    private fun <T> withYtdl(ctl: JobCtl, block: () -> T): T {
        if (!App.ytdlReady.await(180, java.util.concurrent.TimeUnit.SECONDS)) {
            throw FetchError("Downloader ready hoy nai (timeout). App bondho kore abar kholo.")
        }
        if (!App.ytdlOk) {
            ctl.step("Downloader abar start korchi")
            if (!App.initYtdl()) throw FetchError(
                "Downloader (yt-dlp) start hoy nai: ${App.ytdlError}. App uninstall kore abar install koro; " +
                    "tao na hole Settings → Debug info copy kore pathao.")
        }
        val lock = App.ytdlLock.readLock()
        if (!lock.tryLock()) {
            ctl.step("yt-dlp update hocche, ektu wait…")
            lock.lockInterruptibly()
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun execute(req: YoutubeDLRequest, ctl: JobCtl, withProgress: Boolean = false): String {
        val onProgress: (Float, Long, String) -> Unit = { p, eta, line -> ctl.progress(p, eta, line) }
        val cb: ((Float, Long, String) -> Unit)? = if (withProgress) onProgress else null
        try {
            return YoutubeDL.getInstance().execute(req, ctl.id, cb).out
        } catch (e: YoutubeDL.CanceledException) {
            throw Cancelled()
        } catch (e: YoutubeDLException) {
            ctl.check()   // cancel er karone process mara gele
            throw e
        }
    }

    /** Prothom je URL e info pawa jay: (info, url, err) */
    private fun extract(urls: List<String>, ctl: JobCtl): Triple<JSONObject?, String, String> {
        var err = ""
        for (u in urls.distinct()) {
            try {
                val out = execute(request(u).apply { addOption("-J") }, ctl)
                return Triple(JSONObject(out.substring(out.indexOf('{'))), u, "")
            } catch (e: Cancelled) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                err = shortErr(e)
            }
        }
        return Triple(null, urls.first(), err)
    }

    private fun firstEntry(info: JSONObject): JSONObject {
        val entries = info.optJSONArray("entries")
        return if (entries != null && entries.length() > 0) entries.optJSONObject(0) ?: info else info
    }

    private fun str(o: JSONObject?, k: String) = o?.opt(k)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
    private fun num(o: JSONObject?, k: String) = o?.optDouble(k, Double.NaN)?.takeIf { !it.isNaN() && it > 0 }

    fun fetch(url: String, work: File, ctl: JobCtl): Media = withYtdl(ctl) { fetchLocked(url, work, ctl) }

    private fun fetchLocked(url: String, work: File, ctl: JobCtl): Media {
        ctl.step("Link check hocche")
        val original = url
        val resolved = resolveUrl(url)
        val videoLink = isVideoUrl(original) || isVideoUrl(resolved)
        ctl.step("Post info ana hocche")
        val (info, target, err) = extract(listOf(resolved, original), ctl)
        if (info == null) {
            // video link e thumbnail porle bhul list ashe, tai clear error
            if (videoLink) throw FetchError("Video download hoy nai.${loginHint(original)} ($err)")
            ctl.step("Video pawa jay nai, poster/image khujchi")
            ogFallback(target, work)?.let { it.warning = "Video pawa jay nai, post er image/caption theke pora hoyeche."; return it }
            throw FetchError("Link theke kichu ana gelo na.${loginHint(original)} Na hole screenshot Poster tab e dao. ($err)")
        }
        val first = firstEntry(info)
        val cap = caption(info).ifEmpty { caption(first) }
        val duration = num(first, "duration") ?: 0.0
        // vcodec na thakle (FB progressive / direct mp4) video dhore nei; shudhu sob format "none" hole audio-only
        val formats = first.optJSONArray("formats") ?: JSONArray()
        val fmts = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
        val hasVideo = if (fmts.isNotEmpty()) fmts.any { str(it, "vcodec") != "none" } else str(first, "vcodec") != "none"
        val longVideo = duration > MAX_VIDEO_SEC
        val audioOnly = longVideo || !hasVideo

        val req = request(first.optString("webpage_url").ifBlank { target }).apply {
            addOption("-o", File(work, "media_%(autonumber)s.%(ext)s").absolutePath)
            addOption("--playlist-items", "1")
            if (audioOnly) {
                addOption("-f", "ba/b")
                addOption("-x"); addOption("--audio-format", "mp3"); addOption("--audio-quality", "9")
            } else {
                addOption("-f", "b[height<=360]/bv*[height<=360]+ba/b[height<=480]/wv*+ba/w/b")
                addOption("--merge-output-format", "mp4")
            }
        }
        ctl.step(if (audioOnly) "Audio download hocche" else "360p download hocche (AI er jonno)")
        try {
            execute(req, ctl, withProgress = true)
        } catch (e: YoutubeDLException) {
            if (cap.isNotBlank()) return Media("text", emptyList(), cap, info.optString("title"),
                warning = "Video download hoy nai (${shortErr(e).take(120)}), shudhu caption theke pora.")
            throw FetchError("Download fail.${loginHint(original)} (${shortErr(e)})")
        }
        val files = work.listFiles { f -> f.name.startsWith("media_") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") }
            ?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) throw FetchError("Download hoy nai")
        return Media("video", files.take(1), cap, info.optString("title"), audioOnly)
    }

    // ---------------- video download (Snaptube er moto quality select)
    private fun size(f: JSONObject?, duration: Double): Long? {
        f ?: return null
        val s = num(f, "filesize") ?: num(f, "filesize_approx") ?: num(f, "tbr")?.let { t -> if (duration > 0) t * 1000 / 8 * duration else null }
        return s?.toLong()
    }

    /** Quality option: [{label, kind, format, size}] */
    fun formats(url: String, ctl: JobCtl): JSONObject = withYtdl(ctl) {
        ctl.step("Link check hocche")
        val resolved = resolveUrl(url)
        ctl.step("Video info ana hocche")
        val (info, target, err) = extract(listOf(resolved, url), ctl)
        info ?: throw FetchError("Video info pawa gelo na.${loginHint(url)} ($err)")
        val first = firstEntry(info)
        val duration = num(first, "duration") ?: 0.0
        val arr = first.optJSONArray("formats")
        val fmts = if (arr != null && arr.length() > 0) (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } else listOf(first)
        val audio = fmts.filter { str(it, "vcodec") == "none" && str(it, "acodec").let { a -> a != null && a != "none" } }
        val bestAudio = audio.maxByOrNull { num(it, "abr") ?: num(it, "tbr") ?: 0.0 }
        val aSize = size(bestAudio, duration)
        val byP = sortedMapOf<Int, JSONObject>(compareByDescending { it })
        for (f in fmts) {
            if (str(f, "vcodec") == "none" || str(f, "format_id") == null) continue
            val h = num(f, "height")?.toInt() ?: continue
            val w = num(f, "width")?.toInt()
            val p = if (w != null) minOf(w, h) else h          // vertical reel 1080x1920 = 1080p
            val cur = byP[p]
            if (cur == null || (num(f, "tbr") ?: 0.0) > (num(cur, "tbr") ?: 0.0)) byP[p] = f
        }
        val options = JSONArray()
        for ((p, f) in byP) {
            val videoOnly = str(f, "acodec") == "none"
            var s = size(f, duration)
            if (s != null && videoOnly && aSize != null) s += aSize
            val id = f.getString("format_id")
            options.put(JSONObject().put("label", "${p}p").put("kind", "video").put("ext", "mp4")
                .put("size", s ?: JSONObject.NULL).put("format", if (videoOnly) "$id+bestaudio/$id" else id))
        }
        if (options.length() == 0) {
            options.put(JSONObject().put("label", "Best video").put("kind", "video").put("ext", "mp4")
                .put("size", size(first, duration) ?: JSONObject.NULL).put("format", "bv*+ba/b"))
        }
        if (bestAudio != null || fmts.any { str(it, "acodec") != "none" }) {
            options.put(JSONObject().put("label", "MP3").put("kind", "audio").put("ext", "mp3")
                .put("size", aSize ?: JSONObject.NULL).put("format", "ba/b"))
        }
        JSONObject().put("url", str(first, "webpage_url") ?: target)
            .put("title", str(first, "title") ?: str(info, "title") ?: "Video")
            .put("thumbnail", str(first, "thumbnail") ?: str(info, "thumbnail") ?: "")
            .put("duration", duration).put("uploader", str(first, "uploader") ?: "")
            .put("options", options)
    }

    private val FORMAT_OK = Regex("^[\\w+\\-/\\[\\]<>=.*:]{1,120}$")

    /** Select kora quality te download, final file ferot dey */
    fun download(url: String, format: String, kind: String, work: File, ctl: JobCtl): File = withYtdl(ctl) {
        if (!FORMAT_OK.matches(format)) throw FetchError("Bhul format")
        val req = request(url).apply {
            addOption("-f", format)
            addOption("-o", File(work, "%(title).60B [%(id)s].%(ext)s").absolutePath)
            addOption("--playlist-items", "1")
            addOption("--no-mtime")
            if (kind == "audio") {
                addOption("-x"); addOption("--audio-format", "mp3"); addOption("--audio-quality", "0")
            } else {
                addOption("--merge-output-format", "mp4")
            }
        }
        ctl.step("Download hocche")
        try {
            execute(req, ctl, withProgress = true)
        } catch (e: YoutubeDLException) {
            throw FetchError("Download fail.${loginHint(url)} (${shortErr(e)})")
        }
        work.listFiles { f -> f.isFile && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") && !f.name.contains(".f") }
            ?.maxByOrNull { it.length() }
            ?: work.listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.maxByOrNull { it.length() }
            ?: throw FetchError("Download hoy nai")
    }

    // ---------------- photo/poster post: og:image + og:description
    private fun cookieHeader(host: String): String? {
        if (!Store.cookies.exists()) return null
        val pairs = Store.cookies.readLines().mapNotNull { l ->
            val c = l.removePrefix("#HttpOnly_").split("\t")
            if (l.startsWith("#") && !l.startsWith("#HttpOnly_") || c.size < 7) null
            else if (host.endsWith(c[0].trimStart('.'))) "${c[5]}=${c[6]}" else null
        }
        return pairs.joinToString("; ").ifEmpty { null }
    }

    private fun ogFallback(url: String, work: File): Media? {
        val host = try { URI(url).host ?: "" } catch (_: Exception) { "" }
        val attempts = listOf("facebookexternalhit/1.1" to null, UA to cookieHeader(host))
        for ((ua, cookie) in attempts) {
            try {
                val rb = Request.Builder().url(url).header("User-Agent", ua).header("Accept-Language", "en")
                if (cookie != null) rb.header("Cookie", cookie)
                val (finalUrl, html) = Ai.http.newCall(rb.build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null to ""
                    r.request.url.toString() to (r.body?.string() ?: "")
                }
                if (finalUrl == null || finalUrl.contains("login")) continue
                val meta = HashMap<String, String>()
                Regex("<meta[^>]+(?:property|name)=\"(og:[a-z:]+)\"[^>]+content=\"([^\"]*)\"").findAll(html).forEach {
                    meta.putIfAbsent(it.groupValues[1], unescape(it.groupValues[2]))
                }
                val cap = listOfNotNull(meta["og:title"], meta["og:description"]).joinToString("\n")
                val img = meta["og:image"]
                if (img != null) {
                    val f = File(work, "poster.img")
                    val ok = Ai.http.newCall(Request.Builder().url(img).header("User-Agent", UA).build()).execute().use { r ->
                        if (r.isSuccessful) { r.body?.byteStream()?.use { i -> f.outputStream().use { i.copyTo(it) } }; true } else false
                    }
                    if (ok && f.length() > 0) return Media("image", listOf(f), cap, meta["og:title"] ?: "")
                }
                if (cap.isNotBlank()) return Media("text", emptyList(), cap, meta["og:title"] ?: "")
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
