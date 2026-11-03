package com.listkoro.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * App er bhitore FB / Instagram login. "Save" chaple login cookies -> cookies.txt (Netscape),
 * jeta yt-dlp private post download e use kore. Password kothao save hoy na.
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var domain: String
    private lateinit var sessionCookie: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val insta = intent.getStringExtra("site") == "instagram"
        domain = if (insta) "instagram.com" else "facebook.com"
        sessionCookie = if (insta) "sessionid" else "c_user"
        val startUrl = if (insta) "https://www.instagram.com/accounts/login/" else "https://m.facebook.com/"

        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#171A21"))
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "Login koro, tarpor Save chapo"
            setTextColor(Color.parseColor("#E9ECF2"))
            textSize = 15f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "✓ Save"
            isAllCaps = false
            setTextColor(Color.parseColor("#1A0F06"))
            setBackgroundColor(Color.parseColor("#FF8A3D"))
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
        web.loadUrl(startUrl)
    }

    private fun save() {
        val cm = CookieManager.getInstance()
        cm.flush()
        val raw = cm.getCookie("https://www.$domain") ?: ""
        val pairs = raw.split(";").map { it.trim() }.filter { it.contains("=") }
            .map { it.substringBefore("=") to it.substringAfter("=") }
        if (pairs.none { it.first == sessionCookie }) {
            Toast.makeText(this, "Age login complete koro", Toast.LENGTH_SHORT).show()
            return
        }
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val keep = if (Store.cookies.exists())
            Store.cookies.readLines().filter { l ->
                val first = l.removePrefix("#HttpOnly_").substringBefore("\t")
                l.isNotBlank() && !l.startsWith("# Netscape") && !first.trimStart('.').endsWith(domain)
            } else emptyList()
        val fresh = pairs.map { (k, v) -> ".$domain\tTRUE\t/\tTRUE\t$expiry\t$k\t$v" }
        Store.cookies.writeText((listOf("# Netscape HTTP Cookie File") + keep + fresh).joinToString("\n") + "\n")
        Toast.makeText(this, "Saved ✓ Ekhon private post o kaj korbe", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    @Deprecated("Back handling (framework)")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
