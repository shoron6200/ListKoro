package com.listkoro.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * App er bhitorer chhoto server (shudhu 127.0.0.1). Python server er moto same /api route dey,
 * tai server/static er ekoi web UI duijaygay chole.
 */
class LocalServer(private val ctx: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(s: IHTTPSession): Response = try {
        route(s)
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}"))
    }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri
        val m = s.method
        return when {
            m == Method.GET && (uri == "/" || uri == "/index.html") -> asset("index.html")
            m == Method.GET && uri == "/manifest.json" -> asset("manifest.json")
            m == Method.GET && uri.startsWith("/static/") -> asset(uri.removePrefix("/static/"))

            uri == "/api/jobs" && m == Method.POST -> createJob(s)
            uri.startsWith("/api/jobs/") && m == Method.GET ->
                Jobs.get(uri.removePrefix("/api/jobs/"))?.let { json(Response.Status.OK, it) } ?: error(404, "Job pawa jay nai")

            uri == "/api/history" && m == Method.GET -> json(Response.Status.OK, Store.search(s.parameters["q"]?.firstOrNull() ?: ""))
            uri.startsWith("/api/history/") && m == Method.DELETE -> {
                Store.delete(uri.removePrefix("/api/history/").toLongOrNull() ?: -1); ok()
            }

            uri == "/api/settings" && m == Method.GET -> json(Response.Status.OK, settings())
            uri == "/api/settings" && m == Method.POST -> {
                val d = readJson(s)
                val map = HashMap<String, String>()
                for (k in listOf("provider", "gemini_model", "ollama_host", "ollama_model", "ollama_vision_model")) {
                    if (d.has(k)) map[k] = d.optString(k)
                }
                d.optString("gemini_key").takeIf { it.isNotBlank() }?.let { map["gemini_key"] = it }
                Store.set(map)
                json(Response.Status.OK, settings())
            }

            uri == "/api/cookies" && m == Method.POST -> {
                val files = HashMap<String, String>()
                s.parseBody(files)
                val text = files["file"]?.let { File(it).readText() } ?: ""
                if (!text.contains("\t")) return error(400, "Eta Netscape cookies.txt format na.")
                Store.cookies.writeText(if (text.trimStart().startsWith("#")) text else "# Netscape HTTP Cookie File\n$text")
                ok()
            }
            uri == "/api/cookies" && m == Method.DELETE -> { Store.cookies.delete(); ok() }

            else -> error(404, "Not found")
        }
    }

    private fun createJob(s: IHTTPSession): Response {
        val ct = s.headers["content-type"] ?: ""
        if (ct.startsWith("multipart/")) {
            val files = HashMap<String, String>()
            s.parseBody(files)
            // NanoHTTPD: same name er file gula "image", "image2", "image3" ... hisebe ashe
            val keys = files.keys.filter { it == "image" || it.matches(Regex("image\\d+")) }.sortedBy { it.removePrefix("image").toIntOrNull() ?: 0 }
            val saved = keys.mapNotNull { k ->
                val src = File(files[k]!!)
                if (src.length() == 0L) null
                else File(Store.tmp, "up_${UUID.randomUUID()}.img").also { src.copyTo(it, overwrite = true) }
            }
            if (saved.isEmpty()) return error(400, "Image file dao (jpg/png/webp)")
            val extra = s.parameters["text"]?.firstOrNull() ?: ""
            return json(Response.Status.OK, JSONObject().put("job", Jobs.start("image", "", saved, extra)))
        }
        val d = readJson(s)
        val url = d.optString("url").trim()
        val text = d.optString("text").trim()
        return when {
            url.isNotEmpty() -> Fetcher.findUrl(url)?.let { json(Response.Status.OK, JSONObject().put("job", Jobs.start("url", it))) }
                ?: error(400, "Valid link dao (https://...)")
            text.isNotEmpty() -> json(Response.Status.OK, JSONObject().put("job", Jobs.start("text", text)))
            else -> error(400, "Link, text ba image dao")
        }
    }

    private fun settings(): JSONObject {
        val key = Store.get("gemini_key")
        return JSONObject()
            .put("platform", "android")
            .put("provider", Store.get("provider"))
            .put("gemini_model", Store.get("gemini_model"))
            .put("gemini_key_set", key.isNotBlank())
            .put("gemini_key_hint", if (key.length > 8) key.take(4) + "…" + key.takeLast(3) else "")
            .put("ollama_host", Store.get("ollama_host"))
            .put("ollama_model", Store.get("ollama_model"))
            .put("ollama_vision_model", Store.get("ollama_vision_model"))
            .put("tesseract_lang", "")
            .put("cookies", Store.cookies.exists())
            .put("ffmpeg", App.ytdlReady.count == 0L && App.ytdlError == null)
            .put("tesseract", true)
            .put("ocr_label", "ML Kit OCR")
            .put("downloader_label", "yt-dlp")
    }

    /** JSON body nijei UTF-8 e pori (Bangla text thik rakhte). */
    private fun readJson(s: IHTTPSession): JSONObject {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return JSONObject()
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return try { JSONObject(String(buf, 0, read, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
    }

    private fun asset(path: String): Response {
        if (path.contains("..")) return error(404, "Not found")
        return try {
            NanoHTTPD.newChunkedResponse(Response.Status.OK, mime(path), ctx.assets.open(path)).apply {
                addHeader("Cache-Control", "no-cache")
            }
        } catch (_: Exception) {
            error(404, "Not found")
        }
    }

    private fun mime(p: String) = when (p.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    private fun json(st: Response.Status, body: Any): Response =
        NanoHTTPD.newFixedLengthResponse(st, "application/json; charset=utf-8", body.toString())

    private fun ok() = json(Response.Status.OK, JSONObject().put("ok", true))

    private fun error(code: Int, msg: String): Response {
        val st = when (code) { 400 -> Response.Status.BAD_REQUEST; 404 -> Response.Status.NOT_FOUND; else -> Response.Status.INTERNAL_ERROR }
        return json(st, JSONObject().put("error", msg))
    }
}
