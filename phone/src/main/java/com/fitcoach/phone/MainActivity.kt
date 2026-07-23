package com.fitcoach.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class MainActivity : Activity(), DataClient.OnDataChangedListener {

    private lateinit var web: WebView
    private val path = "/progress"

    /** Мост из JS: веб-приложение шлёт прогресс на часы. */
    inner class Bridge {
        @JavascriptInterface
        fun push(sessionsDone: Int) {
            try {
                val req = PutDataMapRequest.create(path).apply {
                    dataMap.putInt("sessionsDone", sessionsDone)
                    dataMap.putLong("ts", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(this@MainActivity).putDataItem(req)
            } catch (e: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            builtInZoomControls = false
            setSupportZoom(false)
        }
        web.addJavascriptInterface(Bridge(), "AndroidSync")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.loadUrl("file:///android_asset/app.html")
        setContentView(web)
        syncNow()
    }

    private fun deliver(sd: Int) {
        runOnUiThread {
            web.evaluateJavascript("window.__fitOnSync && window.__fitOnSync($sd)", null)
        }
    }

    private fun syncNow() {
        try {
            Wearable.getDataClient(this).dataItems.addOnSuccessListener { buf ->
                for (item in buf) {
                    if (item.uri.path == path) {
                        deliver(DataMapItem.fromDataItem(item).dataMap.getInt("sessionsDone", 0))
                    }
                }
                buf.release()
            }
        } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try { Wearable.getDataClient(this).addListener(this) } catch (e: Exception) {}
        syncNow()
    }

    override fun onPause() {
        super.onPause()
        try { Wearable.getDataClient(this).removeListener(this) } catch (e: Exception) {}
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == path) {
                deliver(DataMapItem.fromDataItem(e.dataItem).dataMap.getInt("sessionsDone", 0))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
