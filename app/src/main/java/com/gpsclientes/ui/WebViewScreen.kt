package com.gpsclientes.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.gpsclientes.BuildConfig

@Composable
fun WebViewScreen() {
    if (!BuildConfig.ENABLE_WEBVIEW) {
        // Fallback to legacy Compose if flag false
        return
    }
    AndroidView(factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true // fix fetch: enable JS bridge
            settings.domStorageEnabled = true
            settings.setGeolocationEnabled(true) // fix geoloc
            settings.setGeolocationDatabasePath(context.filesDir.path) // fix geoloc
            @SuppressLint("SetJavaScriptEnabled")
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // fix fetch mixed https->http
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE // fix cache: force no-cache for 0-divergence www
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            clearCache(true) // fix cache: purge WebView cache on create

            addJavascriptInterface(object { // fix fetch: bridge to inject LAN API URL
                @android.webkit.JavascriptInterface
                fun getApiUrl(): String = BuildConfig.API_URL
            }, "Android")

            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
                .build()

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
            }

            webChromeClient = object : WebChromeClient() { // fix geoloc
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, true, false) // fix geoloc: always grant to JS, system permission already handled
                }
            }

            // fix geoloc: optional permission check — do not block, only log (system permission handled externally)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("WebViewScreen", "Location permission not yet granted — WebChromeClient will still grant JS prompt")
            }

            // Load frontend from assets/www via https origin to enable SW and CORS
            loadUrl("https://appassets.androidplatform.net/assets/www/index.html?v=14") // fix cache: v14 visual+rAF
        }
    })
}
