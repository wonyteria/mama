package kr.mom.probe.connector

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object WebsiteSessionManager {
    suspend fun hasStoredSession(definition: SiteDefinition): Boolean = withContext(Dispatchers.Main) {
        definition.allowedHostSuffixes.any { suffix ->
            CookieManager.getInstance().getCookie("https://$suffix/").orEmpty().isNotBlank()
        }
    }
    // WebView shares its cookie jar across these sites. Reset the whole jar and
    // matching connection metadata together, instead of claiming per-path deletion.
    suspend fun clearAll(): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val manager = CookieManager.getInstance()
            manager.removeAllCookies {
                manager.flush()
                WebStorage.getInstance().deleteAllData()
                if (continuation.isActive) continuation.resume(!manager.hasCookies())
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun renderAuthenticatedDom(
        context: Context,
        definition: SiteDefinition,
        url: String,
        script: String,
        timeoutMs: Long,
    ): WebsiteDomRenderResult = withContext(Dispatchers.Main) {
        if (!ConnectorCatalog.isAllowedHttps(definition, url)) {
            return@withContext WebsiteDomRenderResult.BlockedNavigation(url)
        }
        val result = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<WebsiteDomRenderResult> { continuation ->
                    var webView: WebView? = null
                    var finished = false

                    fun destroyView() {
                        val view = webView ?: return
                        webView = null
                        view.stopLoading()
                        view.webViewClient = WebViewClient()
                        view.destroy()
                    }

                    fun finish(result: WebsiteDomRenderResult) {
                        if (finished) return
                        finished = true
                        CookieManager.getInstance().flush()
                        destroyView()
                        if (continuation.isActive) continuation.resume(result)
                    }

                    continuation.invokeOnCancellation { destroyView() }

                    webView = WebView(context.applicationContext).apply {
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
                                finish(WebsiteDomRenderResult.BlockedNavigation(target))
                                return true
                            }

                            override fun onPageFinished(view: WebView, finishedUrl: String) {
                                if (!ConnectorCatalog.isAllowedHttps(definition, finishedUrl)) {
                                    finish(WebsiteDomRenderResult.BlockedNavigation(finishedUrl))
                                    return
                                }
                                view.evaluateJavascript(script) { payload ->
                                    finish(WebsiteDomRenderResult.Success(finishedUrl, payload.orEmpty()))
                                }
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    finish(WebsiteDomRenderResult.RenderError(error.errorCode, error.description?.toString().orEmpty()))
                                }
                            }

                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                if (request.isForMainFrame) {
                                    finish(WebsiteDomRenderResult.HttpError(errorResponse.statusCode, errorResponse.reasonPhrase.orEmpty()))
                                }
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                finish(WebsiteDomRenderResult.RenderError(-1, "WebView renderer exited"))
                                return true
                            }
                        }
                    }
                    webView?.loadUrl(url)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        result ?: WebsiteDomRenderResult.Timeout
    }
}

sealed interface WebsiteDomRenderResult {
    data class Success(val finalUrl: String, val payload: String) : WebsiteDomRenderResult
    data class BlockedNavigation(val url: String) : WebsiteDomRenderResult
    data class HttpError(val statusCode: Int, val reason: String) : WebsiteDomRenderResult
    data class RenderError(val code: Int, val description: String) : WebsiteDomRenderResult
    data object Timeout : WebsiteDomRenderResult
}
