package com.listkoro.app

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

class Cancelled : Exception("Cancel kora hoyeche")

/** Ekta job er control: step log, progress, cancel check. Job id-i yt-dlp er processId (cancel er jonno). */
class JobCtl(val id: String) {
    @Volatile var cancelled = false

    fun check() {
        if (cancelled || Thread.currentThread().isInterrupted) throw Cancelled()
    }

    fun step(msg: String) {
        check()
        Jobs.step(id, msg)
    }

    /** yt-dlp callback: progress %, eta (sec), output line */
    fun progress(pct: Float, eta: Long, line: String) = Jobs.progress(id, pct, eta, line)
}

/**
 * Background job: mode = list | formats | download.
 * UI proti 1s e /api/jobs/<id> theke status, step log, progress pore; /cancel e thamay.
 */
object Jobs {
    private val jobs = ConcurrentHashMap<String, JSONObject>()
    private val ctls = ConcurrentHashMap<String, JobCtl>()
    private val futures = ConcurrentHashMap<String, Future<*>>()
    private val listPool = Executors.newFixedThreadPool(2)
    private val dlPool = Executors.newFixedThreadPool(2)

    private fun now() = System.currentTimeMillis() / 1000.0

    fun get(id: String): JSONObject? = jobs[id]?.let { synchronized(it) { JSONObject(it.toString()) } }

    fun list(activeOnly: Boolean): JSONArray {
        val out = JSONArray()
        jobs.values.map { j -> synchronized(j) { JSONObject(j.toString()) } }
            .filter { !activeOnly || it.optString("status") == "running" }
            .sortedByDescending { it.optDouble("created") }
            .take(50).forEach { out.put(it) }
        return out
    }

    private fun set(id: String, vararg kv: Pair<String, Any?>) {
        val j = jobs[id] ?: return
        synchronized(j) { kv.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) } }
    }

    private fun log(id: String, msg: String) {
        val j = jobs[id] ?: return
        synchronized(j) {
            if (j.optString("step") != msg) {
                val t = Math.round((now() - j.getDouble("created")) * 10) / 10.0
                j.getJSONArray("log").put(JSONObject().put("t", t).put("msg", msg))
            }
        }
    }

    fun step(id: String, msg: String) {
        log(id, msg)
        set(id, "step" to msg, "progress" to null)
    }

    private val SIZE_RE = Regex("of\\s+~?\\s*([\\d.]+)\\s*([KMGT]?i?B)")
    private val SPEED_RE = Regex("at\\s+([\\d.]+)\\s*([KMGT]?i?B)/s")

    private fun bytes(n: String, unit: String): Double {
        val m = when (unit.firstOrNull()) {
            'K' -> 1024.0; 'M' -> 1048576.0; 'G' -> 1073741824.0; 'T' -> 1099511627776.0; else -> 1.0
        }
        return (n.toDoubleOrNull() ?: 0.0) * m
    }

    fun progress(id: String, pct: Float, eta: Long, line: String) {
        val p = JSONObject()
        if (pct >= 0) p.put("pct", Math.round(pct * 10) / 10.0)
        if (eta >= 0) p.put("eta", eta)
        SIZE_RE.find(line)?.let { m ->
            val total = bytes(m.groupValues[1], m.groupValues[2])
            p.put("total", total.toLong())
            if (pct >= 0) p.put("done", (total * pct / 100).toLong())
        }
        SPEED_RE.find(line)?.let { m -> p.put("speed", bytes(m.groupValues[1], m.groupValues[2]).toLong()) }
        set(id, "progress" to p)
    }

    fun cancel(id: String): JSONObject? {
        val j = jobs[id] ?: return null
        if (j.optString("status") == "running") {
            ctls[id]?.cancelled = true
            set(id, "step" to "Cancel hocche…")
            try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Throwable) {}
            futures[id]?.cancel(true)
        }
        return get(id)
    }

    private fun start(mode: String, title: String, meta: Map<String, String> = emptyMap(),
                      task: (JobCtl, File) -> JSONObject?): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val j = JSONObject().put("id", id).put("mode", mode).put("status", "running").put("step", "Shuru hocche")
            .put("created", now()).put("log", JSONArray()).put("progress", JSONObject.NULL).put("title", title)
        meta.forEach { (k, v) -> j.put(k, v) }
        jobs[id] = j
        val ctl = JobCtl(id)
        ctls[id] = ctl
        val cutoff = now() - 6 * 3600
        jobs.entries.removeIf { it.value.optDouble("created") < cutoff && it.value.optString("status") != "running" }
        val pool = if (mode == "download") dlPool else listPool
        futures[id] = pool.submit { run(ctl, task) }
        return id
    }

    private fun finish(ctl: JobCtl, status: String, vararg kv: Pair<String, Any?>) {
        val st = if (ctl.cancelled) "cancelled" else status
        val msg = when (st) { "done" -> "Done ✓"; "cancelled" -> "Cancel hoyeche"; else -> "Error" }
        log(ctl.id, msg)
        set(ctl.id, "status" to st, "step" to msg, "progress" to null)
        if (st == "cancelled") set(ctl.id, "error" to "Cancel kora hoyeche") else set(ctl.id, *kv)
    }

    private fun run(ctl: JobCtl, task: (JobCtl, File) -> JSONObject?) {
        val work = File(Store.tmp, ctl.id).apply { mkdirs() }
        try {
            val result = task(ctl, work)
            ctl.check()
            finish(ctl, "done", "result" to result)
        } catch (e: Cancelled) {
            finish(ctl, "cancelled")
        } catch (e: YoutubeDL.CanceledException) {
            finish(ctl, "cancelled")
        } catch (e: InterruptedException) {
            finish(ctl, "cancelled")
        } catch (e: InterruptedIOException) {
            finish(ctl, "error", "error" to "Network timeout. Abar try koro.")
        } catch (e: AiError) {
            finish(ctl, "error", "error" to e.message)
        } catch (e: FetchError) {
            finish(ctl, "error", "error" to e.message)
        } catch (e: Throwable) {
            Log.e("ListKoro", "job failed", e)
            finish(ctl, "error", "error" to "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            work.deleteRecursively()
            ctls.remove(ctl.id)
            futures.remove(ctl.id)
        }
    }

    // ------------------------------------------------------------------ tasks
    fun startList(kindIn: String, payloadIn: String, images: List<File> = emptyList(), extra: String = ""): String =
        start("list", if (kindIn == "image") "Poster" else payloadIn.take(80)) { ctl, work ->
            var kind = kindIn
            var payload = payloadIn
            try {
                val provider = Ai.provider()
                provider.step = { ctl.step(it) }
                var warning = ""
                if (kind == "text") {
                    val url = Fetcher.findUrl(payload)
                    if (url != null && payload.trim().length <= url.length + 40) { kind = "url"; payload = url }
                }
                val source: String
                val res: ListResult
                when (kind) {
                    "text" -> { ctl.step("AI text porche"); res = provider.fromText(payload); source = payload.take(300) }
                    "image" -> { ctl.step("AI poster/image porche"); res = provider.fromImages(images, extra); source = "image upload" }
                    else -> {
                        source = payload
                        val m = Fetcher.fetch(payload, work, ctl)
                        res = when (m.kind) {
                            "video" -> {
                                ctl.step(if (m.audioOnly) "AI audio shunche" else "AI video dekhche")
                                provider.fromVideo(m.files[0], m.caption, m.audioOnly)
                            }
                            "image" -> { ctl.step("AI poster porche"); provider.fromImages(m.files, m.caption) }
                            else -> { ctl.step("AI caption porche"); provider.fromText(m.caption) }
                        }
                        if (res.title.isBlank()) res.title = m.title.take(80)
                        warning = m.warning
                    }
                }
                ctl.check()  // cancel hole history te save hobe na
                if (res.items.length() == 0) throw AiError("Kono movie/tool er nam pawa gelo na.")
                val rid = Store.add(kind, source, res.title.ifBlank { "Untitled list" }, res.items, provider.name)
                Store.get(rid)?.also { if (warning.isNotBlank()) it.put("warning", warning) }
            } finally {
                images.forEach { it.delete() }
            }
        }

    fun startFormats(url: String): String = start("formats", url) { ctl, _ -> Fetcher.formats(url, ctl) }

    fun startDownload(url: String, format: String, kind: String, label: String, title: String): String =
        start("download", title.ifBlank { url }, mapOf("label" to label, "kind" to kind)) { ctl, work ->
            val f = Fetcher.download(url, format, kind, work, ctl)
            ctl.check()
            ctl.step("Phone e save hocche")
            val name = title.ifBlank { f.nameWithoutExtension }
            val saved = Saver.save(f, kind, name)
            val did = Store.addDownload(url, name, label, kind, saved.name, saved.uri, saved.location, saved.size, saved.mime)
            Store.getDownload(did)
        }
}
