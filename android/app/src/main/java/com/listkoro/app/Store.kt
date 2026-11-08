package com.listkoro.app

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings (SharedPreferences), history (SQLite) ar file path. */
object Store {
    lateinit var ctx: Context
    val prefs: SharedPreferences by lazy { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val db by lazy { DbHelper(ctx) }

    val cookies: File get() = File(ctx.filesDir, "cookies.txt")
    val tmp: File get() = File(ctx.cacheDir, "work").apply { mkdirs() }

    private val DEFAULTS = mapOf(
        "provider" to "gemini",
        "gemini_key" to "",
        "gemini_model" to "gemini-flash-latest",
        "ollama_host" to "http://192.168.0.100:11434",
        "ollama_model" to "qwen2.5:7b",
        "ollama_vision_model" to "",
    )

    fun get(key: String): String = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: DEFAULTS[key] ?: ""

    fun set(updates: Map<String, String>) {
        val e = prefs.edit()
        for ((k, v) in updates) if (k in DEFAULTS) e.putString(k, v.trim())
        e.apply()
    }

    // ---------------- history
    private class DbHelper(c: Context) : SQLiteOpenHelper(c, "listkoro.db", null, 2) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL(
                "CREATE TABLE results(id INTEGER PRIMARY KEY AUTOINCREMENT, created REAL, source_type TEXT, " +
                    "source TEXT, title TEXT, items TEXT, provider TEXT)"
            )
            createDownloads(d)
        }
        override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {
            if (o < 2) createDownloads(d)
        }
        private fun createDownloads(d: SQLiteDatabase) = d.execSQL(
            "CREATE TABLE IF NOT EXISTS downloads(id INTEGER PRIMARY KEY AUTOINCREMENT, created REAL, url TEXT, " +
                "title TEXT, label TEXT, kind TEXT, filename TEXT, uri TEXT, location TEXT, size INTEGER, mime TEXT)"
        )
    }

    fun add(sourceType: String, source: String, title: String, items: JSONArray, provider: String): Long {
        val v = ContentValues().apply {
            put("created", System.currentTimeMillis() / 1000.0)
            put("source_type", sourceType); put("source", source); put("title", title)
            put("items", items.toString()); put("provider", provider)
        }
        return db.writableDatabase.insert("results", null, v)
    }

    fun get(id: Long): JSONObject? = query("SELECT * FROM results WHERE id=?", arrayOf(id.toString())).let {
        if (it.length() > 0) it.getJSONObject(0) else null
    }

    fun search(q: String): JSONArray = if (q.isBlank())
        query("SELECT * FROM results ORDER BY id DESC LIMIT 100", arrayOf())
    else {
        val like = "%$q%"
        query("SELECT * FROM results WHERE title LIKE ? OR items LIKE ? OR source LIKE ? ORDER BY id DESC LIMIT 100",
            arrayOf(like, like, like))
    }

    fun delete(id: Long) { db.writableDatabase.delete("results", "id=?", arrayOf(id.toString())) }

    fun clearHistory() { db.writableDatabase.delete("results", null, null) }

    // ---------------- downloads
    fun addDownload(url: String, title: String, label: String, kind: String, filename: String, uri: String,
                    location: String, size: Long, mime: String): Long {
        val v = ContentValues().apply {
            put("created", System.currentTimeMillis() / 1000.0)
            put("url", url); put("title", title); put("label", label); put("kind", kind); put("filename", filename)
            put("uri", uri); put("location", location); put("size", size); put("mime", mime)
        }
        return db.writableDatabase.insert("downloads", null, v)
    }

    private fun dlRows(where: String?, args: Array<String>?): JSONArray {
        val out = JSONArray()
        db.readableDatabase.query("downloads", null, where, args, null, null, "id DESC", "200").use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                for (i in 0 until c.columnCount) {
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(c.getColumnName(i), c.getDouble(i))
                        else -> o.put(c.getColumnName(i), c.getString(i) ?: "")
                    }
                }
                out.put(o)
            }
        }
        return out
    }

    fun downloads(): JSONArray = dlRows(null, null)

    fun getDownload(id: Long): JSONObject? = dlRows("id=?", arrayOf(id.toString())).optJSONObject(0)

    fun deleteDownload(id: Long) { db.writableDatabase.delete("downloads", "id=?", arrayOf(id.toString())) }

    private fun query(sql: String, args: Array<String>): JSONArray {
        val out = JSONArray()
        db.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject().apply {
                    put("id", c.getLong(c.getColumnIndexOrThrow("id")))
                    put("created", c.getDouble(c.getColumnIndexOrThrow("created")))
                    put("source_type", c.getString(c.getColumnIndexOrThrow("source_type")))
                    put("source", c.getString(c.getColumnIndexOrThrow("source")))
                    put("title", c.getString(c.getColumnIndexOrThrow("title")))
                    put("items", JSONArray(c.getString(c.getColumnIndexOrThrow("items")) ?: "[]"))
                    put("provider", c.getString(c.getColumnIndexOrThrow("provider")))
                })
            }
        }
        return out
    }
}
