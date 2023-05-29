package com.diggsey.dpass.webview

import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebView
import com.diggsey.dpass.RequestCode

interface IDPassReadyReceiver {
    fun onDPassReady()
}

class LoggingChromeClient(
    private val readyReceiver: IDPassReadyReceiver,
    private val webViewInterface: WebViewInterface
) : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val priority = when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.TIP -> Log.VERBOSE
            ConsoleMessage.MessageLevel.LOG, null -> Log.INFO
            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
            ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
        }
        val message =
            "${consoleMessage.message()}\n\tat ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
        Log.println(priority, "WebViewConsole", message)

        if (consoleMessage.message() == "dpass ready") {
            readyReceiver.onDPassReady()
        }

        return true
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        val intent = fileChooserParams.createIntent()
        intent.putExtra(Intent.EXTRA_MIME_TYPES, fileChooserParams.acceptTypes)
        webViewInterface.startActivityViaProxy(intent, RequestCode.SELECT_FILE,
            object : IActivityResultCallback {
                override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
                    filePathCallback.onReceiveValue(FileChooserParams.parseResult(resultCode, data))
                }

                override fun onCancel() {
                    filePathCallback.onReceiveValue(null)
                }
            }
        )
        return true
    }
}