package com.diggsey.dpass.webview

import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.Uri
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebMessagePort.WebMessageCallback
import android.webkit.WebView
import com.diggsey.dpass.BackgroundService
import com.diggsey.dpass.MainActivity
import org.json.JSONObject
import org.json.JSONStringer

class WebViewInterface(
    private val context: Context,
    private val webView: WebView,
    private val origin: String
) : WebMessageCallback(), ServiceConnection, SharedPreferences.OnSharedPreferenceChangeListener {
    private var hostPort: WebMessagePort? = null
    private val bufferedMessages = ArrayList<WebMessage>()
    private var boundBackgroundService = false
    private var backgroundService: BackgroundService? = null
    private var bufferedActions = ArrayList<(BackgroundService) -> Unit>()
    private var nextRequestId: Long = 1
    private val responseHandlers = HashMap<String, IResponseHandler>()
    private var sharedPreferences: SharedPreferences? = null

    inner class MessageResponseHandler(private val requestId: String) : IResponseHandler {
        override fun onResponse(jsonStr: String) {
            postRawMessage("response", requestId, jsonStr)
        }

        override fun onError(jsonStr: String) {
            postRawMessage("error", requestId, jsonStr)
        }

    }

    fun inject() {
        Log.i("WebViewInterface", "Injecting...")
        val preferencesName = "${context.packageName}_preferences"
        sharedPreferences = context.getSharedPreferences(preferencesName, MODE_PRIVATE)

        val (host, client) = webView.createWebMessageChannel()
        webView.postWebMessage(
            WebMessage("inject", arrayOf(client)),
            Uri.parse(origin)
        )
        hostPort = host.also {
            it.setWebMessageCallback(this)
            while (bufferedMessages.isNotEmpty()) {
                val message = bufferedMessages.removeFirst()
                it.postMessage(message)
            }
        }

        sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
    }

    private fun handleResponseOrError(prefix: String, requestId: String, jsonStr: String) {
        val handler = responseHandlers.remove(requestId)
        if (handler != null) {
            if (prefix == "response") {
                handler.onResponse(jsonStr)
            } else {
                handler.onError(jsonStr)
            }
        }
    }

    override fun onMessage(port: WebMessagePort, message: WebMessage) {
        val (prefix, requestId, jsonStr) = message.data.split(":", limit = 3)

        when (prefix) {
            "connect" -> handleConnect(message.ports!![0], jsonStr)
            "message" -> handleMessage(requestId, jsonStr)
            "response", "error" -> handleResponseOrError(prefix, requestId, jsonStr)
            "writeStorage" -> handleWriteStorage(requestId, jsonStr)
            "readStorage" -> handleReadStorage(requestId, jsonStr)
            "beginDownload" -> handleDownload(jsonStr)
            else -> {
                Log.e("WebViewInterface", "Unknown message prefix: $prefix")
            }
        }
    }

    private fun handleDownload(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val filename = json["filename"] as String
        val contentType = json["contentType"] as String
        val data = Base64.decode(json["data"] as String, Base64.DEFAULT)
        val tempFilename = context.cacheDir.resolve(filename)
        tempFilename.writeBytes(data)

        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.putExtra("action", "download")
        intent.putExtra("filename", tempFilename.path)
        intent.putExtra("contentType", contentType)
        context.startActivity(intent)
    }

    private fun bindBackgroundService(block: (BackgroundService) -> Unit) {
        if (this.backgroundService != null) {
            block(this.backgroundService!!)
        } else {
            this.bufferedActions.add(block)
            if (!boundBackgroundService) {
                boundBackgroundService = true
                val intent = Intent(context, BackgroundService::class.java)
                context.bindService(
                    intent, this, Context.BIND_AUTO_CREATE
                )
            }
        }

    }

    private fun handleConnect(webMessagePort: WebMessagePort, jsonStr: String) {
        bindBackgroundService {
            it.connect(webMessagePort, jsonStr)
        }
    }

    private fun handleMessage(requestId: String, jsonStr: String) {
        bindBackgroundService {
            it.message(jsonStr, MessageResponseHandler(requestId))
        }
    }

    private fun postRawMessage(
        prefix: String,
        requestId: String,
        jsonStr: String,
        ports: Array<WebMessagePort> = emptyArray()
    ) {
        val msg = WebMessage("$prefix:$requestId:$jsonStr", ports)
        if (this.hostPort == null) {
            this.bufferedMessages.add(msg)
        } else {
            this.hostPort!!.postMessage(msg)
        }
    }

    fun connect(webMessagePort: WebMessagePort, jsonStr: String) {
        postRawMessage("connect", "", jsonStr, arrayOf(webMessagePort))
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        backgroundService = (binder as BackgroundService.LocalBinder).getService().also {
            while (bufferedActions.isNotEmpty()) {
                val action = bufferedActions.removeFirst()
                action(it)
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        boundBackgroundService = false
        backgroundService = null
    }

    fun destroy() {
        if (boundBackgroundService) {
            context.unbindService(this)
        }
    }

    fun request(prefix: String, jsonStr: String, handler: IResponseHandler) {
        val requestId = (nextRequestId++).toString()
        responseHandlers[requestId] = handler
        postRawMessage(prefix, requestId, jsonStr)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        val message = JSONObject()
        message.put("key", key)
        postRawMessage("storageChanged", "", message.toString())
    }

    private fun handleReadStorage(requestId: String, jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val key = json["key"] as String
            val value = sharedPreferences!!.getString(key, "null")
            postRawMessage("response", requestId, value!!)
        } catch (ex: Exception) {
            postRawMessage("error", requestId, JSONObject.quote(ex.toString()))
        }
    }

    private fun encodeJson(value: Any): String {
        return if (value is String) {
            JSONObject.quote(value)
        } else {
            value.toString()
        }
    }

    private fun handleWriteStorage(requestId: String, jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val key = json["key"] as String
            val value = json["value"]
            with(sharedPreferences!!.edit()) {
                if (value == JSONObject.NULL || value == null) {
                    remove(key)
                } else {
                    putString(key, encodeJson(value))
                }
                commit()
            }
            postRawMessage("response", requestId, "null")
        } catch (ex: Exception) {
            postRawMessage("error", requestId, JSONStringer().value(ex.toString()).toString())
        }
    }

}

interface IResponseHandler {
    fun onResponse(jsonStr: String)
    fun onError(jsonStr: String)
}