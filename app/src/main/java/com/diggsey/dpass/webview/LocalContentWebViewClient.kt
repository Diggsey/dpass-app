package com.diggsey.dpass.webview

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

class LocalContentWebViewClient(context: Context) :
    WebViewClientCompat() {

    companion object {
        const val DEFAULT_ORIGIN = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}"
    }

    private val assetLoader = WebViewAssetLoader.Builder().addPathHandler(
        "/",
        WebViewAssetLoader.AssetsPathHandler(context)
    ).build()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (request.url.path == "/favicon.ico") {
            return WebResourceResponse(null, null, null)
        }
        return assetLoader.shouldInterceptRequest(request.url)
    }
}