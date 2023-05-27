package com.diggsey.dpass

import android.R.attr
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import com.diggsey.dpass.webview.AppHost
import com.diggsey.dpass.webview.IDPassHostActivity
import com.diggsey.dpass.webview.IFileChooserCallback
import java.io.File
import java.io.OutputStream


class MainActivity : Activity(), IDPassHostActivity {
    private val appHost = AppHost(this, this)
    private var activeFileSelection: IFileChooserCallback? = null
    private var activeFileDownload: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        WebView.setWebContentsDebuggingEnabled(true)

        super.onCreate(savedInstanceState)
        appHost.init("src/entries/options/index.html")
        setContentView(appHost.webView)

        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.getStringExtra("action")
        when (action) {
            "download" -> {
                val filename = intent.getStringExtra("filename")
                val contentType = intent.getStringExtra("contentType")
                if (filename != null && contentType != null) {
                    handleDownloadRequest(filename, contentType)
                }
            }
        }
    }

    private fun handleDownloadRequest(filename: String, contentType: String) {
        val file = File(filename)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = contentType
        intent.putExtra(Intent.EXTRA_TITLE, file.name)
        activeFileDownload = file
        startActivityForResult(intent, RequestCode.DOWNLOAD_FILE)
    }

    private fun handleDownload(file: File, data: Intent) {
        val uri = data.data as Uri
        if (uri != null) {
            val os = contentResolver.openOutputStream(uri)
            if (os != null) {
                os.write(file.readBytes())
                os.close()
            }
        }
        file.delete()
    }

    override fun onDestroy() {
        appHost.destroy()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            RequestCode.SELECT_FILE -> {
                activeFileSelection?.also {
                    it.onResult(resultCode, data)
                }
                activeFileSelection = null
            }

            RequestCode.DOWNLOAD_FILE -> {
                activeFileDownload?.also {
                    if (data != null) {
                        handleDownload(it, data)
                    }
                }
                activeFileDownload = null
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onNewIntent(intent: Intent) {
        handleIntent(intent)
    }

    override fun onShowFileChooser(
        callback: IFileChooserCallback,
        intent: Intent
    ): Boolean {
        activeFileSelection?.also {
            it.onCancel()
        }
        activeFileSelection = callback
        startActivityForResult(intent, RequestCode.SELECT_FILE)
        return true
    }
}