package com.gmssh.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader

/**
 * 客户端授权（client-auth）用的内置浏览器。
 * 命中 gmssh://auth/callback?... 后把完整回调地址回传给 MainActivity，
 * 由 MainActivity 通过 main-to-render 推回页面。
 */
class AuthActivity : Activity() {

    companion object {
        private const val TAG = "GMSSH-Auth"
        const val EXTRA_AUTH_URL = "auth_url"
        const val EXTRA_CALLBACK_URL = "callback_url"
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString + MainActivity.UA_SUFFIX
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme?.lowercase() == "gmssh") {
                    Log.i(TAG, "授权回调命中: $uri")
                    finishWithCallback(uri.toString())
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && url.startsWith("gmssh://")) finishWithCallback(url)
            }
        }

        val url = intent.getStringExtra(EXTRA_AUTH_URL)
        if (url.isNullOrEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        webView.loadUrl(url)
    }

    private fun finishWithCallback(callbackUrl: String) {
        if (isFinishing) return
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_CALLBACK_URL, callbackUrl)
        )
        finish()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else {
            setResult(RESULT_CANCELED)
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
}
