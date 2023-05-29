package com.diggsey.dpass.webview

import android.R.attr.text
import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.accounts.AccountManagerFuture
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebMessagePort.WebMessageCallback
import android.webkit.WebView
import com.diggsey.dpass.BackgroundService
import com.diggsey.dpass.KeyBox
import com.diggsey.dpass.MainActivity
import com.diggsey.dpass.RequestCode
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener


interface IActivityResultCallback {
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    fun onCancel()
}

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
    var isRefreshBlocked = false
        private set

    companion object {
        private var activeCallback: IActivityResultCallback? = null
    }

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
            "requestToken" -> handleRequestToken(requestId, jsonStr)
            "blockRefresh" -> handleBlockRefresh(jsonStr)
            "copyText" -> handleCopyText(requestId, jsonStr)
            "rememberKey" -> handleRememberKey(requestId, jsonStr)
            "requestUnlock" -> handleRequestUnlock(requestId)
            "openApp" -> handleOpenApp(requestId)
            else -> {
                Log.e("WebViewInterface", "Unknown message prefix: $prefix")
            }
        }
    }

    private fun handleRequest(requestId: String, block: () -> String?) {
        try {
            val result = block()
            if (result != null) {
                postRawMessage("response", requestId, result)
            }
        } catch (err: Exception) {
            postRawMessage("error", requestId, JSONObject.quote(err.toString()))
        }
    }

    private fun decodeJson(jsonStr: String): Any {
        return JSONTokener(jsonStr).nextValue()
    }

    private fun handleOpenApp(requestId: String) {
        handleRequest(requestId) {
            context.startActivity(Intent(context, MainActivity::class.java))
            ""
        }
    }

    private fun handleRequestUnlock(requestId: String) {
        handleRequest(requestId) {
            KeyBox.recallSecureValue(context) {
                postRawMessage(
                    "unlockWithKey",
                    "",
                    JSONObject.quote(Base64.encodeToString(it, Base64.DEFAULT))
                )
            }
            ""
        }
    }

    private fun handleRememberKey(requestId: String, jsonStr: String) {
        handleRequest(requestId) {
            KeyBox.rememberSecureValue(
                context,
                Base64.decode(decodeJson(jsonStr) as String, Base64.DEFAULT)
            )
            ""
        }
    }

    private fun handleCopyText(requestId: String, jsonStr: String) {
        handleRequest(requestId) {
            val clipboard =
                context.getSystemService(ClipboardManager::class.java)
            val text = decodeJson(jsonStr) as String
            val clip = ClipData.newPlainText("dpass", text)
            clipboard.setPrimaryClip(clip)

            ""
        }
    }

    private fun handleBlockRefresh(jsonStr: String) {
        isRefreshBlocked = jsonStr == "true"
    }

    inner class OauthCallback(
        private val serverId: String,
        private var userId: String,
        private val requestId: String
    ) : AccountManagerCallback<Bundle>, IActivityResultCallback {

        fun begin() {
            if (userId == "") {
                val chooseAccountIntent = AccountManager.newChooseAccountIntent(
                    null,
                    null,
                    arrayOf(serverId),
                    null,
                    null,
                    null,
                    null
                )
                startActivityViaProxy(chooseAccountIntent, RequestCode.CHOOSE_ACCOUNT, this)
            } else {
                onChooseAccount(userId)
            }
        }

        private fun onComplete(token: String?) {
            if (token == null) {
                postRawMessage("error", requestId, JSONObject.quote("Cancelled"))
            } else {
                val tokenObj = JSONObject()
                tokenObj.put("id", "oauth")
                tokenObj.put("accessToken", token)
                val connInfoObj = JSONObject()
                connInfoObj.put("id", "oauth")
                connInfoObj.put("serverId", serverId)
                connInfoObj.put("userId", userId)
                val res = JSONArray()
                res.put(tokenObj)
                res.put(connInfoObj)
                postRawMessage("response", requestId, res.toString())
            }

        }

        private fun onChooseAccount(userId: String) {
            this.userId = userId
            val account = Account(userId, serverId)
            val scope = when (serverId) {
                "google" -> "oauth2:https://www.googleapis.com/auth/drive.file"
                else -> "oauth2:openid"
            }
            AccountManager.get(context)
                .getAuthToken(account, scope, null, true, this, null)
        }

        override fun run(future: AccountManagerFuture<Bundle>) {
            val bundle: Bundle = future.result

            val launch: Intent? =
                bundle.getParcelable(AccountManager.KEY_INTENT, Intent::class.java)
            if (launch != null) {
                startActivityViaProxy(launch, RequestCode.AUTH_CODE, this)
            } else {
                val token = bundle
                    .getString(AccountManager.KEY_AUTHTOKEN)
                onComplete(token)
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            when (requestCode) {
                RequestCode.CHOOSE_ACCOUNT -> {
                    if (data != null) {
                        onChooseAccount(
                            data
                                .getStringExtra(AccountManager.KEY_ACCOUNT_NAME)!!
                        )
                    } else {
                        onChooseAccount(userId)
                    }
                }

                RequestCode.AUTH_CODE -> {
                    onChooseAccount(userId)
                }
            }
        }

        override fun onCancel() {
            onComplete(null)
        }
    }

    fun startActivityViaProxy(
        intent: Intent,
        requestCode: Int,
        callback: IActivityResultCallback
    ) {
        activeCallback?.also { it.onCancel() }
        activeCallback = callback
        when (context) {
            is Activity -> context.startActivityForResult(intent, requestCode)
            else -> {
                val proxyIntent = Intent(context, MainActivity::class.java)
                proxyIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                proxyIntent.putExtra("requestCode", requestCode)
                proxyIntent.putExtra("action", "startIntent")
                proxyIntent.putExtra("intent", intent)
                context.startActivity(proxyIntent)
            }
        }
    }

    private fun handleRequestToken(requestId: String, jsonStr: String) {

        val json = JSONObject(jsonStr)
        when (json.getString("id")) {
            "none" -> {}
            "oauth" -> {
                val serverId = json.getString("serverId")
                val userId = json.getString("userId")
                OauthCallback(serverId, userId, requestId).begin()
            }
        }
    }

    private fun handleDownload(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val filename = json.getString("filename")
        val contentType = json.getString("contentType")
        val fileBytes = Base64.decode(json.getString("data"), Base64.DEFAULT)

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = contentType
        intent.putExtra(Intent.EXTRA_TITLE, filename)

        startActivityViaProxy(intent, RequestCode.DOWNLOAD_FILE, object : IActivityResultCallback {
            override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
                when (requestCode) {
                    RequestCode.DOWNLOAD_FILE -> {
                        val uri = data?.data
                        if (uri != null) {
                            val os = context.contentResolver.openOutputStream(uri)
                            if (os != null) {
                                os.write(fileBytes)
                                os.close()
                            }
                        }
                    }
                }
            }

            override fun onCancel() {}
        })
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
        handleRequest(requestId) {
            val json = JSONObject(jsonStr)
            val key = json.getString("key")
            sharedPreferences!!.getString(key, "null")!!
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
        handleRequest(requestId) {
            val json = JSONObject(jsonStr)
            val key = json.getString("key")
            val value = json["value"]
            with(sharedPreferences!!.edit()) {
                if (value == JSONObject.NULL) {
                    remove(key)
                } else {
                    putString(key, encodeJson(value))
                }
                commit()
            }
            ""
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        activeCallback?.also {
            it.onActivityResult(requestCode, resultCode, data)
        }
        activeCallback = null
    }

    fun command(commandId: String) {
        bindBackgroundService {
            it.appHost.handleCommand(commandId)
        }
    }

    fun handleCommand(commandId: String) {
        postRawMessage("executeCommand", "", JSONObject.quote(commandId))
    }
}

interface IResponseHandler {
    fun onResponse(jsonStr: String)
    fun onError(jsonStr: String)
}