package com.gmssh.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class MainActivity : Activity() {

    companion object {
        private const val TAG = "GMSSH"
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/assets/dist/index.html"
        private const val SCHEME = "gmssh"
        private const val AUTH_REQUEST = 1001
        const val UA_SUFFIX = " GMSSH-Android/2.0.6"

        /** 供 AuthActivity 回传授权回调地址 */
        const val EXTRA_CALLBACK_URL = "callback_url"
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var shimJs: String
    private var pendingAuthUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        shimJs = runCatching {
            assets.open("gm_shim.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#222222"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        webView = WebView(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        configureWebView()

        handleIntent(intent, initial = true)

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL)
        }
    }

    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        s.setSupportMultipleWindows(true)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString = s.userAgentString + UA_SUFFIX
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.parseColor("#222222"))
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        // 关键：document-start 注入垫片，保证任何页面脚本执行前 electronAPI 已存在
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && shimJs.isNotEmpty()) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, shimJs, setOf("*"))
            }.onFailure { Log.w(TAG, "document-start 注入失败: ${it.message}") }
        }

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = handleUrl(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 兜底：不支持 document-start 的内核在这里补注入
                if (shimJs.isNotEmpty()) {
                    view.evaluateJavascript(shimJs, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (shimJs.isNotEmpty()) {
                    view.evaluateJavascript(shimJs, null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "[console] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }

            // window.open / target=_blank：用临时 WebView 承接后，导航回当前 WebView
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@MainActivity)
                popup.webViewClient = object : WebViewClientCompat() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean = handleUrl(request.url)

                    override fun shouldInterceptRequest(
                        v: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
                }
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.settings.userAgentString = popup.settings.userAgentString + UA_SUFFIX
                popup.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
                popup.webChromeClient = this
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    /** 站内（appassets）继续在 WebView 内；gmssh:// 拦截为授权回调；其余交系统浏览器 */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == SCHEME) {
            Log.i(TAG, "拦截深链: $uri")
            dispatchCallback(uri.toString())
            return true
        }
        if (scheme == "http" || scheme == "https") {
            if (uri.host == HOST) return false
            openExternally(uri)
            return true
        }
        // mailto:, tel:, intent: 等交给系统
        return runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrDefault(false)
    }

    private fun openExternally(uri: Uri) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(this, "没有可打开该链接的应用", Toast.LENGTH_SHORT).show()
        }
    }

    /** 授权回调 -> 推给页面（main-to-render / client-auth-callback） */
    private fun dispatchCallback(url: String) {
        if (!::webView.isInitialized) return
        val payload = JSONObject().apply {
            put("type", "client-auth-callback")
            put("data", url)
        }
        val js = "window.__gmDispatchMainToRender && window.__gmDispatchMainToRender(${JSONObject.quote(payload.toString())});"
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    private fun handleIntent(intent: Intent?, initial: Boolean) {
        val data = intent?.data ?: return
        if (data.scheme?.lowercase() == SCHEME) {
            Log.i(TAG, "启动深链: $data")
            pendingAuthUrl = data.toString()
            if (!initial) dispatchCallback(data.toString())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, initial = false)
    }

    /** 用内置 WebView 打开授权页，命中 gmssh:// 时把结果回传 */
    fun startClientAuth(url: String) {
        pendingAuthUrl = url
        val i = Intent(this, AuthActivity::class.java).putExtra(AuthActivity.EXTRA_AUTH_URL, url)
        startActivityForResult(i, AUTH_REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AUTH_REQUEST) {
            val cb = data?.getStringExtra(EXTRA_CALLBACK_URL)
            if (resultCode == RESULT_OK && !cb.isNullOrEmpty()) {
                Log.i(TAG, "授权回调: $cb")
                dispatchCallback(cb)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        pendingAuthUrl?.let { url ->
            if (url.startsWith("http")) {
                pendingAuthUrl = null
                startClientAuth(url)
            } else if (url.startsWith("$SCHEME://")) {
                pendingAuthUrl = null
                dispatchCallback(url)
            }
        }
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /* 原生 -> JS 推送                                                     */
    /* ------------------------------------------------------------------ */
    private fun dispatchMainToRender(type: String, dataJson: String) {
        val payload = JSONObject().apply {
            put("type", type)
            put("data", dataJson)
        }
        val js = "window.__gmDispatchMainToRender && window.__gmDispatchMainToRender(${JSONObject.quote(payload.toString())});"
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val n = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(n) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /* ------------------------------------------------------------------ */
    /* JS 桥                                                               */
    /* ------------------------------------------------------------------ */
    inner class AndroidBridge {

        @android.webkit.JavascriptInterface
        fun invoke(requestId: String?, payloadJson: String?): String {
            val payload = runCatching { JSONObject(payloadJson ?: "{}") }.getOrNull()
                ?: return "{\"status\":\"ok\"}"
            val type = payload.optString("type", "")
            val data = payload.opt("data")
            Log.i(TAG, "render-to-main: $type")

            return when (type) {
                "client-auth-ready" -> "{\"status\":\"ok\"}"

                "open-browser", "open-browser-auth" -> {
                    val url = extractUrl(data)
                    if (url != null) {
                        runOnUiThread {
                            if (isAuthUrl(url)) startClientAuth(url) else openExternally(Uri.parse(url))
                        }
                    }
                    "{\"status\":\"ok\"}"
                }

                "client-auth-start" -> {
                    val url = extractUrl(data) ?: "https://api.gmssh.com/client-auth/authorize"
                    runOnUiThread { startClientAuth(url) }
                    "{\"status\":\"ok\"}"
                }

                "get-build-env" -> "\"pro\""

                "get-app-exit-to-tray" -> "{\"status\":\"ok\",\"data\":false}"

                "set-app-exit-to-tray", "set-window-size", "set-gm-cookie",
                "cache-resource-update-payload", "update-quick-change-tab",
                "update-quick-copy", "update-quick-paste", "update-term-font-size",
                "sync-app-language" -> "{\"status\":\"ok\"}"

                "get-port" -> "{\"status\":\"ok\",\"gmPort\":0,\"gmServerStatus\":false}"

                "get-resource-versions" -> "{\"status\":\"ok\",\"data\":{}}"

                "check-update" -> "{\"status\":\"ok\",\"update\":false,\"message\":\"已是最新版本\"}"

                "close-program" -> {
                    runOnUiThread { finish() }
                    "{\"status\":\"ok\"}"
                }

                "webview-ready", "webview-close", "open-desktop-view" -> "{\"status\":\"ok\"}"

                else -> "{\"status\":\"ok\"}"
            }
        }

        @android.webkit.JavascriptInterface
        fun isNetworkAvailable(): Boolean = this@MainActivity.isNetworkAvailable()

        @android.webkit.JavascriptInterface
        fun platformInfo(): String {
            val chrome = runCatching {
                webView.settings.userAgentString.substringAfter("Chrome/", "").substringBefore(" ")
            }.getOrDefault("126.0.0.0")
            return JSONObject().apply {
                put("node", "20.0.0")
                put("chrome", chrome.ifEmpty { "126.0.0.0" })
                put("electron", "31.0.0")
                put("platform", "android")
                put("arch", "arm64")
                put("distPath", "/android_asset/dist")
            }.toString()
        }

        @android.webkit.JavascriptInterface
        fun log(message: String?) {
            Log.i(TAG, "[js] $message")
        }

        @android.webkit.JavascriptInterface
        fun toast(message: String?) {
            if (message.isNullOrEmpty()) return
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun extractUrl(data: Any?): String? {
        when (data) {
            is String -> if (data.startsWith("http")) return data
            is JSONObject -> {
                for (key in listOf("url", "authUrl", "webUrl", "href", "data")) {
                    val v = data.optString(key, "")
                    if (v.startsWith("http")) return v
                }
            }
        }
        return null
    }

    private fun isAuthUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/client-auth/") || u.contains("client-auth") || u.contains("/oauth")
    }
}
