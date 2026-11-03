package com.listkoro.app

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Background job: link/text/image -> list. UI proti 1.5s e status jiggesh kore. */
object Jobs {
    private val jobs = ConcurrentHashMap<String, JSONObject>()
    private val pool = Executors.newFixedThreadPool(2)

    fun get(id: String): JSONObject? = jobs[id]?.let { synchronized(it) { JSONObject(it.toString()) } }

    private fun set(id: String, vararg kv: Pair<String, Any?>) {
        val j = jobs[id] ?: return
        synchronized(j) { kv.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) } }
    }

    fun start(kind: String, payload: String, images: List<File> = emptyList(), extra: String = ""): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        jobs[id] = JSONObject().put("id", id).put("status", "running").put("step", "Shuru hocche")
            .put("created", System.currentTimeMillis())
        val cutoff = System.currentTimeMillis() - 3600_000
        jobs.entries.removeIf { it.value.optLong("created") < cutoff }
        pool.execute { run(id, kind, payload, images, extra) }
        return id
    }

    private fun run(id: String, kindIn: String, payloadIn: String, images: List<File>, extra: String) {
        val work = File(Store.tmp, id).apply { mkdirs() }
        val step = { s: String -> set(id, "step" to s) }
        var kind = kindIn
        var payload = payloadIn
        try {
            val provider = Ai.provider()
            if (kind == "text") {
                val url = Fetcher.findUrl(payload)
                if (url != null && payload.trim().length <= url.length + 40) { kind = "url"; payload = url }
            }
            val source: String
            val res: ListResult
            when (kind) {
                "text" -> { step("AI text porche"); res = provider.fromText(payload); source = payload.take(300) }
                "image" -> { step("AI poster/image porche"); res = provider.fromImages(images, extra); source = "image upload" }
                else -> {
                    source = payload
                    val m = Fetcher.fetch(payload, work, step)
                    res = when (m.kind) {
                        "video" -> {
                            step(if (m.audioOnly) "AI audio shunche" else "AI video dekhche")
                            provider.fromVideo(m.files[0], m.caption, m.audioOnly)
                        }
                        "image" -> { step("AI poster porche"); provider.fromImages(m.files, m.caption) }
                        else -> { step("AI caption porche"); provider.fromText(m.caption) }
                    }
                    if (res.title.isBlank()) res.title = m.title.take(80)
                }
            }
            if (res.items.length() == 0) throw AiError("Kono movie/tool er nam pawa gelo na.")
            val rid = Store.add(kind, source, res.title.ifBlank { "Untitled list" }, res.items, provider.name)
            set(id, "status" to "done", "step" to "Done", "result" to Store.get(rid))
        } catch (e: AiError) {
            set(id, "status" to "error", "error" to e.message)
        } catch (e: FetchError) {
            set(id, "status" to "error", "error" to e.message)
        } catch (e: Throwable) {
            Log.e("ListKoro", "job failed", e)
            set(id, "status" to "error", "error" to "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            work.deleteRecursively()
            images.forEach { it.delete() }
        }
    }
}
