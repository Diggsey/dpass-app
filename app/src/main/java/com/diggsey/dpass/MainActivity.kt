package com.diggsey.dpass

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.diggsey.dpass.webview.AppHost


class MainActivity : Activity(), SwipeRefreshLayout.OnRefreshListener {
    private val appHost = AppHost(this)
    private var refreshLayout: CustomRefreshLayout? = null

    @SuppressLint("ViewConstructor")
    class CustomRefreshLayout(context: Context, private val appHost: AppHost) :
        SwipeRefreshLayout(context) {

        init {
            addView(
                appHost.webView,
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }

        override fun canChildScrollUp(): Boolean {
            return appHost.isRefreshBlocked
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WebView.setWebContentsDebuggingEnabled(true)

        super.onCreate(savedInstanceState)
        appHost.init("src/entries/options/index.html")

        refreshLayout = CustomRefreshLayout(this, appHost).also {
            it.setOnRefreshListener(this)
            setContentView(it)
        }

        val autofillManager = getSystemService(AutofillManager::class.java)
        if (autofillManager.isAutofillSupported && !autofillManager.hasEnabledAutofillServices()) {
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            intent.data = Uri.parse("package:com.diggsey.dpass")
            startActivity(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.getStringExtra("action")
        when (action ?: intent.action) {
            "startIntent" -> {
                val requestCode = intent.getIntExtra("requestCode", 0)
                val nestedIntent: Intent? = intent.getParcelableExtra("intent", Intent::class.java)
                if (nestedIntent != null) {
                    startActivityForResult(nestedIntent, requestCode)
                }
            }
        }
    }

    override fun onDestroy() {
        appHost.destroy()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        appHost.onActivityResult(requestCode, resultCode, data)
    }

    override fun onNewIntent(intent: Intent) {
        handleIntent(intent)
    }

    override fun onRefresh() {
        appHost.command("dpass-sync")
        refreshLayout!!.isRefreshing = false
    }
}