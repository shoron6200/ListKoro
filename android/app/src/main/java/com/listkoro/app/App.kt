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
        @Volatile var port = 17865
        private var server: LocalServer? = null
    }

    override fun onCreate() {
        super.onCreate()
        Store.ctx = this
        startServer()
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
            } catch (e: Throwable) {
                Log.e("ListKoro", "yt-dlp init failed", e)
                ytdlError = e.message ?: e.toString()
            } finally {
                ytdlReady.countDown()
            }
            if (ytdlError == null) updateYtDlp()
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
        try {
            YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE)
            Store.prefs.edit().putLong("ytdl_updated", System.currentTimeMillis()).apply()
        } catch (e: Throwable) {
            Log.w("ListKoro", "yt-dlp update failed", e)
        }
    }
}
