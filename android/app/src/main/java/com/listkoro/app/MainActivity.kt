package com.listkoro.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val PICK = 1
        private const val LOGIN = 2
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        web = WebView(this)
        web.setBackgroundColor(0xFF0F1115.toInt())
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
        }
        web.addJavascriptInterface(Bridge(), "ListKoroApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.host == "127.0.0.1") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: ActivityNotFoundException) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
                fileCb?.onReceiveValue(emptyArray())
                fileCb = cb
                val pick = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent.createChooser(pick, "Poster / screenshot"), PICK)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCb = null
                    false
                }
            }
        }
        if (saved != null) web.restoreState(saved) else load(handleShare(intent))
    }

    private fun load(query: String) = web.loadUrl("http://127.0.0.1:${App.port}/$query")

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val q = handleShare(intent)
        if (q.isNotEmpty()) load(q)
    }

    /** Onno app theke Share kora link/text/image -> job shuru, "?job=id" ferot. */
    private fun handleShare(i: Intent?): String {
        if (i == null) return ""
        val type = i.type ?: ""
        val job = when (i.action) {
            Intent.ACTION_SEND -> if (type.startsWith("image/")) {
                streamUri(i)?.let { u -> copyIn(listOf(u)) }?.takeIf { it.isNotEmpty() }
                    ?.let { Jobs.start("image", "", it, i.getStringExtra(Intent.EXTRA_TEXT) ?: "") }
            } else {
                val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT))
                    .joinToString(" ").trim()
                when {
                    text.isEmpty() -> null
                    Fetcher.findUrl(text) != null -> Jobs.start("url", Fetcher.findUrl(text)!!)
                    else -> Jobs.start("text", text)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> streamUris(i).let { copyIn(it) }.takeIf { it.isNotEmpty() }
                ?.let { Jobs.start("image", "", it) }
            else -> null
        }
        i.action = Intent.ACTION_MAIN // rotate/recreate e abar job na hoy
        return if (job != null) "?job=$job" else ""
    }

    @Suppress("DEPRECATION")
    private fun streamUri(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamUris(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
         else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun copyIn(uris: List<Uri>): List<File> = uris.take(10).mapNotNull { u ->
        try {
            val f = File(Store.tmp, "up_${UUID.randomUUID()}.img")
            contentResolver.openInputStream(u)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            f.takeIf { it.length() > 0 }
        } catch (_: Exception) { null }
    }

    @Deprecated("Activity result API (framework)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PICK -> {
                val uris = ArrayList<Uri>()
                if (resultCode == RESULT_OK && data != null) {
                    val clip = data.clipData
                    if (clip != null) for (k in 0 until clip.itemCount) uris += clip.getItemAt(k).uri
                    else data.data?.let { uris += it }
                }
                fileCb?.onReceiveValue(uris.toTypedArray())
                fileCb = null
            }
            LOGIN -> web.evaluateJavascript("window.onNativeLogin && window.onNativeLogin()", null)
        }
    }

    @Deprecated("Back handling (framework)")
    override fun onBackPressed() {
        web.evaluateJavascript("window.listkoroBack ? window.listkoroBack() : false") { handled ->
            if (handled == "true") return@evaluateJavascript
            if (web.canGoBack()) web.goBack() else {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        web.saveState(out)
    }

    /** JS theke native kaj: clipboard, share, login. */
    inner class Bridge {
        private val clip get() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        @JavascriptInterface
        fun paste(): String = try {
            clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        } catch (_: Exception) { "" }

        @JavascriptInterface
        fun copy(text: String) = runOnUiThread { clip.setPrimaryClip(ClipData.newPlainText("ListKoro", text)) }

        @JavascriptInterface
        fun share(text: String) = runOnUiThread {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
        }

        @JavascriptInterface
        fun toast(text: String) = runOnUiThread { Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show() }

        @JavascriptInterface
        fun login(site: String) = runOnUiThread {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this@MainActivity, LoginActivity::class.java).putExtra("site", site), LOGIN)
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME
    }
}
