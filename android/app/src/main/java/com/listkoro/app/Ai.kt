package com.listkoro.app

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class AiError(msg: String) : Exception(msg)

/** Ekta list result: title + items (JSON array, Python version er moto same shape). */
class ListResult(var title: String, val items: JSONArray)

interface Provider {
    val name: String
    fun fromText(text: String): ListResult
    fun fromImages(files: List<File>, context: String): ListResult
    fun fromVideo(file: File, context: String, audioOnly: Boolean): ListResult
}

object Ai {
    const val PROMPT = """You extract lists of named things from social-media content (Facebook/Instagram/YouTube reels, posts, posters, captions).
Such content usually recommends MOVIES, SERIES, ANIME, AI TOOLS, APPS, WEBSITES, BOOKS etc. Language may be Bangla, Hindi, English or mixed.
Look at EVERYTHING: spoken words, on-screen text, posters, logos, and the caption/description.

Return ONLY valid JSON in this exact shape:
{"title": "short title for this list (e.g. 'Top 10 Thriller Movies' or 'AI Video Tools')",
 "items": [{"name": "official name", "type": "movie|series|anime|tool|app|website|book|other",
            "year": "release year if known else empty", "note": "max 12 words: what it is / why recommended",
            "timestamp": "mm:ss where it appears in the video, else empty"}]}

Rules:
- Include every distinct recommended item, in the order they appear. No duplicates.
- Fix OCR / speech spelling mistakes to the real official name (e.g. 'lnterstel1ar' -> 'Interstellar', 'chat gpt' -> 'ChatGPT').
- Do NOT invent items that are not in the content. Ignore the creator's own name, page names, hashtags, 'like & follow' text.
- If nothing is found, return {"title": "", "items": []}."""

    private val TYPES = setOf("movie", "series", "anime", "tool", "app", "website", "book", "other")

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    fun provider(): Provider = when (Store.get("provider")) {
        "ollama" -> Ollama()
        "basic" -> Basic()
        else -> Gemini()
    }

    fun context(c: String) = if (c.isBlank()) "" else "\n\nPost caption / extra text:\n${c.trim()}"

    /** LLM er reply theke JSON ber kora (```json fence ba extra lekha thakleo). */
    fun parse(raw: String): ListResult {
        val t = raw.trim().replace(Regex("^```(?:json)?|```$", RegexOption.MULTILINE), "").trim()
        var obj: JSONObject? = null
        val order = listOf('{' to '}', '[' to ']').sortedBy { (o, _) -> t.indexOf(o).let { if (it < 0) Int.MAX_VALUE else it } }
        for ((o, c) in order) {
            val s = t.indexOf(o); val e = t.lastIndexOf(c)
            if (s < 0 || e <= s) continue
            try {
                val sub = t.substring(s, e + 1)
                obj = if (o == '{') JSONObject(sub) else JSONObject().put("items", JSONArray(sub))
                break
            } catch (_: JSONException) {}
        }
        obj ?: throw AiError("AI er reply JSON chilo na: " + t.take(200))
        val items = JSONArray()
        val seen = HashSet<String>()
        val src = obj.optJSONArray("items") ?: JSONArray()
        for (i in 0 until src.length()) {
            val it = src.opt(i)
            val o = if (it is JSONObject) it else JSONObject().put("name", it?.toString() ?: "")
            val name = o.optString("name").trim()
            if (name.isEmpty() || !seen.add(name.lowercase())) continue
            val type = o.optString("type", "other").lowercase().trim().let { if (it in TYPES) it else "other" }
            items.put(JSONObject().apply {
                put("name", name); put("type", type)
                put("year", o.optString("year").trim().removeSuffix(".0"))
                put("note", o.optString("note").trim())
                put("timestamp", o.optString("timestamp").trim())
            })
        }
        return ListResult(obj.optString("title").trim(), items)
    }

    fun sniffMime(f: File): String {
        val b = ByteArray(12)
        f.inputStream().use { it.read(b) }
        return when {
            b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
            b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() -> "image/webp"
            b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "image/gif"
            else -> "image/jpeg"
        }
    }

    fun mediaMime(f: File) = when (f.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mp3"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "video/mp4"
    }
}

private val JSON = "application/json; charset=utf-8".toMediaType()

// ------------------------------------------------------------------ Gemini (free tier)
class Gemini : Provider {
    override val name = "gemini"
    private val base = "https://generativelanguage.googleapis.com"
    private val key = Store.get("gemini_key")
    private val model = Store.get("gemini_model")

    init {
        if (key.isBlank()) throw AiError("Gemini API key nai. Settings e key dao (free: aistudio.google.com/apikey)")
    }

    private fun generate(parts: JSONArray): ListResult {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Ai.PROMPT))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        val req = Request.Builder().url("$base/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key).post(body.toString().toRequestBody(JSON)).build()
        var code = 0
        var text = ""
        for (attempt in 0 until 3) {
            Ai.http.newCall(req).execute().use { r -> code = r.code; text = r.body?.string() ?: "" }
            if ((code == 429 || code >= 500) && attempt < 2) { Thread.sleep(5000L * (attempt + 1)); continue }
            break
        }
        if (code == 429) throw AiError("Gemini free limit shesh (429). Ektu pore try koro.")
        if (code !in 200..299) throw AiError("Gemini error $code: ${text.take(300)}")
        return try {
            val p = JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            Ai.parse((0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text") })
        } catch (e: JSONException) {
            throw AiError("Gemini kono answer dey nai: ${text.take(300)}")
        }
    }

    private fun upload(f: File): JSONObject {
        val mime = Ai.mediaMime(f)
        val start = Request.Builder().url("$base/upload/v1beta/files")
            .header("x-goog-api-key", key)
            .header("X-Goog-Upload-Protocol", "resumable").header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", f.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(JSONObject().put("file", JSONObject().put("display_name", f.name)).toString().toRequestBody(JSON)).build()
        val upUrl = Ai.http.newCall(start).execute().use { r ->
            if (!r.isSuccessful) throw AiError("Gemini upload start fail ${r.code}: ${r.body?.string()?.take(200)}")
            r.header("x-goog-upload-url") ?: throw AiError("Gemini upload URL pawa jay nai")
        }
        val up = Request.Builder().url(upUrl)
            .header("X-Goog-Upload-Offset", "0").header("X-Goog-Upload-Command", "upload, finalize")
            .post(f.asRequestBody(mime.toMediaType())).build()
        var file = Ai.http.newCall(up).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) throw AiError("Gemini upload fail ${r.code}: ${s.take(200)}")
            JSONObject(s).getJSONObject("file")
        }
        repeat(90) {
            when (file.optString("state")) {
                "ACTIVE" -> return file
                "FAILED" -> throw AiError("Gemini video process korte parlo na")
            }
            Thread.sleep(2000)
            val g = Request.Builder().url("$base/v1beta/${file.getString("name")}").header("x-goog-api-key", key).build()
            file = Ai.http.newCall(g).execute().use { r -> JSONObject(r.body?.string() ?: "{}") }
        }
        throw AiError("Gemini video processing timeout")
    }

    private fun delete(file: JSONObject) {
        try {
            val d = Request.Builder().url("$base/v1beta/${file.getString("name")}").header("x-goog-api-key", key).delete().build()
            Ai.http.newCall(d).execute().close()
        } catch (_: Exception) {}
    }

    private fun text(t: String) = JSONObject().put("text", t)

    override fun fromText(text: String) = generate(JSONArray().put(text("Extract the list from this text:\n\n$text")))

    override fun fromImages(files: List<File>, context: String): ListResult {
        val parts = JSONArray()
        for (f in files) {
            val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", Ai.sniffMime(f)).put("data", b64)))
        }
        parts.put(text("Extract the list from these image(s) (posters / screenshots)." + Ai.context(context)))
        return generate(parts)
    }

    override fun fromVideo(file: File, context: String, audioOnly: Boolean): ListResult {
        val f = upload(file)
        try {
            val what = if (audioOnly) "Listen to this whole audio" else "Watch and listen to this whole video"
            return generate(JSONArray()
                .put(JSONObject().put("file_data", JSONObject().put("mime_type", f.optString("mimeType")).put("file_uri", f.getString("uri"))))
                .put(text("$what and extract the list." + Ai.context(context))))
        } finally {
            delete(f)
        }
    }
}

// ------------------------------------------------------------------ Ollama (PC te chole, phone theke LAN e)
class Ollama : Provider {
    override val name = "ollama"
    private val host = Store.get("ollama_host").trimEnd('/')
    private val model = Store.get("ollama_model")
    private val vmodel = Store.get("ollama_vision_model")

    private fun chat(text: String, images: List<File> = emptyList(), m: String = model): ListResult {
        val msg = JSONObject().put("role", "user").put("content", text)
        if (images.isNotEmpty()) msg.put("images", JSONArray(images.map { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }))
        val body = JSONObject().put("model", m).put("stream", false).put("format", "json")
            .put("options", JSONObject().put("temperature", 0.2))
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", Ai.PROMPT)).put(msg))
        val req = Request.Builder().url("$host/api/chat").post(body.toString().toRequestBody(JSON)).build()
        val s = try {
            Ai.http.newCall(req).execute().use { r ->
                val t = r.body?.string() ?: ""
                if (!r.isSuccessful) throw AiError("Ollama error ${r.code}: ${t.take(200)}")
                t
            }
        } catch (e: java.io.IOException) {
            throw AiError("Ollama e connect hoy nai ($host). PC te 'OLLAMA_HOST=0.0.0.0 ollama serve' chalao, same WiFi te thako.")
        }
        return Ai.parse(JSONObject(s).getJSONObject("message").getString("content"))
    }

    override fun fromText(text: String) = chat("Extract the list from this text:\n\n$text")

    override fun fromImages(files: List<File>, context: String): ListResult =
        if (vmodel.isNotBlank()) chat("Extract the list from these image(s)." + Ai.context(context), files, vmodel)
        else chat("OCR text from poster/screenshot (may have OCR errors):\n" + Ocr.images(files).joinToString("\n") + Ai.context(context))

    override fun fromVideo(file: File, context: String, audioOnly: Boolean): ListResult {
        if (audioOnly) throw AiError("Ollama mode e lomba video (shudhu audio) bujha jay na. Gemini use koro.")
        return chat("On-screen text from video frames (OCR, may have errors):\n" + Ocr.video(file).joinToString("\n") + Ai.context(context))
    }
}

// ------------------------------------------------------------------ Basic (AI chara, shudhu OCR)
class Basic : Provider {
    override val name = "basic"
    private fun wrap(lines: List<String>, title: String) = ListResult(title, JSONArray().apply {
        Ocr.dedupe(lines).forEach { put(JSONObject().put("name", it).put("type", "other").put("year", "").put("note", "").put("timestamp", "")) }
    })

    override fun fromText(text: String) = wrap(Ocr.clean(text.lines()), "Text lines")
    override fun fromImages(files: List<File>, context: String) = wrap(Ocr.images(files), "OCR (no AI)")
    override fun fromVideo(file: File, context: String, audioOnly: Boolean): ListResult {
        if (audioOnly) throw AiError("Basic mode e audio bujha jay na. Gemini use koro.")
        return wrap(Ocr.video(file), "Video OCR (no AI)")
    }
}
