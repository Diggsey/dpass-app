package com.diggsey.dpass

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.webkit.WebMessagePort
import com.diggsey.dpass.webview.AppHost
import com.diggsey.dpass.webview.IResponseHandler

class BackgroundService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): BackgroundService {
            return this@BackgroundService
        }
    }

    val binder = LocalBinder()
    var handler: Handler? = null
    var appHost = AppHost(this)

    private fun post(r: Runnable) {
        handler!!.post(r)
    }

    override fun onCreate() {
//        thread.start()
//        handler = Handler(thread.looper)
        handler = Handler(mainLooper)
        post {
            appHost.init("background.html")
        }
    }

    override fun onDestroy() {
        post {
            appHost.destroy()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun connect(webMessagePort: WebMessagePort, jsonStr: String) {
        post {
            appHost.connect(webMessagePort, jsonStr)
        }
    }

    fun message(jsonStr: String, handler: IResponseHandler) {
        post {
            appHost.message(jsonStr, handler)
        }
    }
}