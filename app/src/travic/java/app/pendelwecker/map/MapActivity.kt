package app.pendelwecker.map

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.pendelwecker.BuildConfig

/**
 * Variante A: TRAVIC per WebView.
 *
 * NUR MIT ERLAUBNIS DER BETREIBER VERÖFFENTLICHEN (Anfrage an Patrick Brosi, Uni Freiburg).
 * Wie man einen Startausschnitt per URL vorgibt, ist nicht dokumentiert – erst nach Antwort
 * der Betreiber in [startUrl] einbauen.
 */
class MapActivity : ComponentActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " Pendelblick/${BuildConfig.VERSION_NAME}"
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                // Nur TRAVIC selbst in der App öffnen. Links der Hauptseite auf andere Seiten (Quellen) im
                // Browser, und nur http/https: intent://, market:// usw. starten keine fremden Apps.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    val web = url.scheme == "https" || url.scheme == "http"
                    if (web && url.host == TRAVIC_HOST) return false
                    if (web && request.isForMainFrame) openExternal(url)
                    return true
                }
            }
        }
        val credit = TextView(this).apply {
            text = "Karte: TRAVIC, Universität Freiburg · Daten: Verkehrsunternehmen"
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xCCFFFFFF.toInt())
            setTextColor(0xFF202124.toInt())
            setOnClickListener { openExternal(Uri.parse(BuildConfig.TRAVIC_URL)) }
        }
        val root = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(credit, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState == null) web.loadUrl(startUrl()) else web.restoreState(savedInstanceState)
    }

    private fun startUrl(): String = BuildConfig.TRAVIC_URL

    private fun openExternal(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            // Kein Browser installiert: Link ignorieren statt abstürzen.
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() { web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val TRAVIC_HOST = "travic.app"
    }
}
