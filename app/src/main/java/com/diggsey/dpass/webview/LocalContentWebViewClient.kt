package com.diggsey.dpass.webview

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LocalContentWebViewClient(context: Context) :
    WebViewClientCompat() {

    companion object {
        const val DEFAULT_ORIGIN = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}"
        val dateFormatter = SimpleDateFormat("E, dd MMM yyyy kk:mm:ss", Locale.US)

        init {
            dateFormatter.timeZone = TimeZone.getTimeZone("GMT")
        }
    }

    private val assetLoader = WebViewAssetLoader.Builder().addPathHandler(
        "/",
        WebViewAssetLoader.AssetsPathHandler(context)
    ).build()

    private fun buildOptionsAllowResponse(): WebResourceResponse {
        val date = Date()
        val dateString = dateFormatter.format(date)

        val headers = hashMapOf(
            "Connection" to "close",
            "Content-Type" to "text/plain",
            "Date" to "$dateString GMT",
            "Access-Control-Allow-Origin" to DEFAULT_ORIGIN,
            "Access-Control-Allow-Methods" to "GET, POST, DELETE, PUT, OPTIONS",
            "Access-Control-Max-Age" to "600",
            "Access-Control-Allow-Credentials" to "true",
            "Access-Control-Allow-Headers" to "accept, authorization, content-type, if-match",
            "Via" to "1.1 vegur",
        )

        return WebResourceResponse("text/plain", "UTF-8", 200, "OK", headers, null)

    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (request.method.equals("OPTIONS", ignoreCase = true)) {
            return buildOptionsAllowResponse()
        } else if (request.url.path == "/favicon.ico") {
            return WebResourceResponse(null, null, null)
        }
        return assetLoader.shouldInterceptRequest(request.url)
    }
}