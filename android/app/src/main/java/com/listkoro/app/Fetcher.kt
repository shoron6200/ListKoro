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

    private fun loginHint() = if (Store.cookies.exists()) " Cookies expire hoye thakte pare: Settings → Facebook e login abar koro."
                              else " Private/login-only video hote pare: Settings → Facebook e login koro."

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

    fun fetch(url: String, work: File, step: (String) -> Unit): Media {
        if (!App.ytdlReady.await(180, java.util.concurrent.TimeUnit.SECONDS)) {
            throw FetchError("Downloader ready hoy nai: ${App.ytdlError ?: "timeout"}")
        }
        step("Link check hocche")
        val original = url
        var target = resolveUrl(url)
        val videoLink = isVideoUrl(original) || isVideoUrl(target)
        step("Post info ana hocche")
        var info: JSONObject? = null
        var err = ""
        for (u in listOf(target, original).distinct()) {  // resolved age, na hole original
            try {
                val r = request(u).apply { addOption("-J") }
                val out = YoutubeDL.getInstance().execute(r, null, null).out
                info = JSONObject(out.substring(out.indexOf('{')))
                target = u
                break
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                err = shortErr(e)
            }
        }
        if (info == null) {
            // video link e thumbnail porle bhul list ashe, tai clear error
            if (videoLink) throw FetchError("Video download hoy nai.${loginHint()} ($err)")
            step("Video pawa jay nai, poster/image khujchi")
            ogFallback(target, work)?.let { it.warning = "Video pawa jay nai, post er image/caption theke pora hoyeche."; return it }
            throw FetchError("Link theke kichu ana gelo na.${loginHint()} Na hole screenshot Poster tab e dao. ($err)")
        }
        val entries = info.optJSONArray("entries")
        val first = if (entries != null && entries.length() > 0) entries.optJSONObject(0) ?: info else info
        val cap = caption(info).ifEmpty { caption(first) }
        val duration = first.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it }
        val formats = first.optJSONArray("formats") ?: JSONArray()
        val hasVideo = (0 until formats.length()).any { formats.optJSONObject(it)?.optString("vcodec", "none") !in listOf("none", "", "null") } ||
            first.optString("vcodec", "none") !in listOf("none", "", "null")
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
        step(if (audioOnly) "Audio download hocche" else "360p download hocche")
        try {
            val onProgress: (Float, Long, String) -> Unit = { progress, _, _ ->
                if (progress > 0) step("Downloading ${progress.toInt()}%")
            }
            YoutubeDL.getInstance().execute(req, null, onProgress)
        } catch (e: YoutubeDLException) {
            if (cap.isNotBlank()) return Media("text", emptyList(), cap, info.optString("title"),
                warning = "Video download hoy nai (${shortErr(e).take(120)}), shudhu caption theke pora.")
            throw FetchError("Download fail.${loginHint()} (${shortErr(e)})")
        }
        val files = work.listFiles { f -> f.name.startsWith("media_") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") }
            ?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) throw FetchError("Download hoy nai")
        return Media("video", files.take(1), cap, info.optString("title"), audioOnly)
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
