package kr.mom.probe.connector

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.mom.probe.BuildConfig

class WebsiteLoginActivity : ComponentActivity() {
    private lateinit var definition: SiteDefinition
    private lateinit var webView: WebView
    private lateinit var confirm: Button
    private lateinit var status: TextView
    private var lastAllowedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        definition = ConnectorCatalog.site(intent.getStringExtra(EXTRA_SITE_ID).orEmpty())
            ?.takeIf { it.available && it.id != "neis-parent" } ?: run { finish(); return }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(246,244,240)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val close = Button(this).apply { text = "닫기"; setOnClickListener { finish() } }
        status = TextView(this).apply { text = "${definition.name}에서 직접 로그인해주세요"; textSize = 15f; setTextColor(Color.rgb(40,61,54)); setPadding(dp(10),0,dp(10),0) }
        val progress = ProgressBar(this).apply { visibility = android.view.View.GONE }
        bar.addView(close, LinearLayout.LayoutParams(dp(76), dp(52)))
        bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(progress, LinearLayout.LayoutParams(dp(32), dp(32)))

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (ConnectorCatalog.isAllowedHttps(definition, target)) return false
                    if (request.url.scheme == "https") startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    else Toast.makeText(this@WebsiteLoginActivity, "공식 HTTPS 주소만 열 수 있어요.", Toast.LENGTH_SHORT).show()
                    return true
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { progress.visibility = android.view.View.VISIBLE }
                override fun onPageFinished(view: WebView, url: String) {
                    progress.visibility = android.view.View.GONE
                    if (ConnectorCatalog.isAllowedHttps(definition, url)) {
                        lastAllowedUrl = url
                        confirm.isEnabled = true
                        status.text = "로그인 후 아래 버튼을 눌러주세요 · 자동 확인은 지원 전"
                        CookieManager.getInstance().flush()
                    }
                }
                override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
                    callback.backToSafety(true)
                    Toast.makeText(this@WebsiteLoginActivity, "안전하지 않은 페이지를 차단했어요.", Toast.LENGTH_LONG).show()
                }
            }
        }
        confirm = Button(this).apply {
            text = "로그인 마쳤어요"
            isEnabled = false
            setOnClickListener {
                val observed = lastAllowedUrl ?: return@setOnClickListener
                lifecycleScope.launch {
                    // User navigation is not proof of authentication. Keep only origin,
                    // never query strings or fragments that may carry credentials.
                    val origin = android.net.Uri.parse(observed).let { "https://${it.host}/" }
                    val stored = ConnectorRepository.get(this@WebsiteLoginActivity).markObserved(definition.id, origin, true)
                    if (stored) { setResult(RESULT_OK, Intent().putExtra(EXTRA_SITE_ID, definition.id)); finish() }
                    else Toast.makeText(this@WebsiteLoginActivity, "연결 상태를 저장하지 못했어요.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { setMargins(dp(16),dp(8),dp(16),dp(12)) })
        setContentView(root)
        webView.loadUrl(definition.startUrl)
    }

    override fun onDestroy() { if (::webView.isInitialized) { webView.stopLoading(); webView.clearCache(true); webView.destroy() }; super.onDestroy() }

    companion object { const val EXTRA_SITE_ID = "site_id" }
}
