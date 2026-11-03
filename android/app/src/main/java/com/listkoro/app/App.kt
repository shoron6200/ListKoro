package com.listkoro.app

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch

class App : Application() {
    companion object {
        val ytdlReady = CountDownLatch(1)
        @Volatile var ytdlError: String? = null
        @Volatile var ytdlOk = false
        @Volatile var port = 17865
        /** yt-dlp chalano (read) ar update (write) eksathe na hoy: update er majhe chalale EOFError hoy */
        val ytdlLock = java.util.concurrent.locks.ReentrantReadWriteLock()
        private var server: LocalServer? = null
        private lateinit var instance: App

        /** yt-dlp + ffmpeg init. Bar bar call kora safe; fail hole asol karon ytdlError e thake. */
        @Synchronized
        fun initYtdl(): Boolean {
            if (ytdlOk) return true
            try {
                YoutubeDL.getInstance().init(instance)
                FFmpeg.getInstance().init(instance)
                ytdlOk = true
                ytdlError = null
            } catch (e: Throwable) {
                Log.e("ListKoro", "yt-dlp init failed", e)
                ytdlError = causeChain(e)
            }
            return ytdlOk
        }

        fun causeChain(e: Throwable): String {
            val parts = ArrayList<String>()
            var t: Throwable? = e
            while (t != null && parts.size < 5) {
                parts += "${t.javaClass.simpleName}: ${t.message ?: ""}".trim()
                t = t.cause?.takeIf { it !== t }
            }
            return parts.joinToString(" ← ").take(600)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.ctx = this
        startServer()
        Thread {
            try { initYtdl() } finally { ytdlReady.countDown() }
            if (ytdlOk) updateYtDlp()
        }.start()
    }

    private fun startServer() {
        for (p in 17865..17880) {
            try {
                server = LocalServer(this, p).apply { start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
                port = p
                return
            } catch (e: Exception) {
                Log.w("ListKoro", "port $p busy", e)
            }
        }
    }

    /** FB/IG change hole yt-dlp bhange, tai dine ekbar update. */
    private fun updateYtDlp() {
        val last = Store.prefs.getLong("ytdl_updated", 0)
        if (System.currentTimeMillis() - last < 24 * 3600_000L) return
        ytdlLock.writeLock().lock()
        try {
            YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE)
            Store.prefs.edit().putLong("ytdl_updated", System.currentTimeMillis()).apply()
        } catch (e: Throwable) {
            Log.w("ListKoro", "yt-dlp update failed", e)
        } finally {
            ytdlLock.writeLock().unlock()
        }
    }
}
