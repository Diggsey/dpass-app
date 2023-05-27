package com.diggsey.dpass.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.WebMessagePort
import android.webkit.WebView

interface IFileChooserCallback {
    fun onResult(resultCode: Int, data: Intent?)
    fun onCancel()
}

interface IDPassHostActivity {
    fun onShowFileChooser(
        callback: IFileChooserCallback,
        intent: Intent
    ): Boolean
}

class AppHost(private val context: Context, private val hostActivity: IDPassHostActivity? = null) :
    IDPassReadyReceiver {
    private var maybeWebView: WebView? = null
    private var webViewClient: LocalContentWebViewClient? = null
    private var webViewInterface: WebViewInterface? = null

    val webView: WebView
        get() = maybeWebView!!

    @SuppressLint("SetJavaScriptEnabled")
    fun init(entryPoint: String) {
        maybeWebView = WebView(context).also {
            webViewClient = LocalContentWebViewClient(context)
            webViewInterface =
                WebViewInterface(context, it, LocalContentWebViewClient.DEFAULT_ORIGIN)
            it.settings.javaScriptEnabled = true
            it.settings.databaseEnabled = true
            it.settings.domStorageEnabled = true
            it.settings.allowContentAccess = true
            it.settings.allowFileAccess = true
            it.webViewClient = webViewClient!!
            it.webChromeClient = LoggingChromeClient(
                this, hostActivity
            )
            val url =
                "${LocalContentWebViewClient.DEFAULT_ORIGIN}/${entryPoint}"
            it.loadUrl(url)
        }

    }

    override fun onDPassReady() {
        webViewInterface!!.inject()
    }

    fun connect(webMessagePort: WebMessagePort, jsonStr: String) {
        webViewInterface!!.connect(webMessagePort, jsonStr)
    }

    fun message(jsonStr: String, handler: IResponseHandler) {
        webViewInterface!!.request("message", jsonStr, handler)
    }

    fun destroy() {
        webViewInterface!!.destroy()
    }
}
