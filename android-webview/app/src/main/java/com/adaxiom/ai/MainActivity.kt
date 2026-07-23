package com.adaxiom.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    companion object {
        private const val HOME_URL = "https://voiceassistant.adaxiomtech.com/"
        private const val AUDIO_PERMISSION_REQUEST = 1001
    }

    private lateinit var webView: WebView
    private var pendingWebPermission: PermissionRequest? = null

    private fun grantAudioCapture(request: PermissionRequest) {
        val requestedAudio = request.resources.filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }.toTypedArray()
        if (requestedAudio.isEmpty()) {
            request.deny()
            return
        }
        // Grant must run on the UI thread (caller already ensures that).
        request.grant(requestedAudio)
        Log.d("AdaxiomAI", "Granted WebView audio capture to ${request.origin}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Helpful while debugging getUserMedia in Chrome inspect; off for release builds.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(2, 6, 23))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            // Needed on some OEMs so mic capture works inside WebView.
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.userAgentString = "${settings.userAgentString} AdaxiomAiAndroidWebView/1.0"

            webViewClient = AdaxiomWebViewClient()
            webChromeClient = AdaxiomWebChromeClient()
        }

        setContentView(webView)
        // MIUI may not create the decor insets controller until content is attached.
        // Post this work to the view queue so the decor is fully initialized.
        webView.post { enterFullscreen() }

        // Ask for mic up front so the first Call button is not racing permission dialogs.
        ensureAndroidMicPermission()

        if (savedInstanceState == null) webView.loadUrl(HOME_URL) else webView.restoreState(savedInstanceState)
    }

    private fun ensureAndroidMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    override fun onDestroy() {
        pendingWebPermission?.deny()
        webView.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != AUDIO_PERMISSION_REQUEST) return

        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        val request = pendingWebPermission
        pendingWebPermission = null

        if (!granted) {
            Log.w("AdaxiomAI", "Android microphone permission was denied")
            request?.deny()
            return
        }

        // If WebView already asked while we were prompting, grant it now.
        request?.let(::grantAudioCapture)
    }

    private fun enterFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun isTrustedAppUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        // HOME_URL is voiceassistant.*; keep markme.* for older installs / redirects.
        return host == "voiceassistant.adaxiomtech.com" ||
            host.endsWith(".voiceassistant.adaxiomtech.com") ||
            host == "markme.adaxiomtech.com" ||
            host.endsWith(".markme.adaxiomtech.com") ||
            host == "adaxiomtech.com" ||
            host.endsWith(".adaxiomtech.com")
    }

    private inner class AdaxiomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme == "https" && isTrustedAppUrl(uri)) return false

            startActivity(Intent(Intent.ACTION_VIEW, uri))
            return true
        }
    }

    private inner class AdaxiomWebChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                Log.d(
                    "AdaxiomAI",
                    "WebView permission request from ${request.origin}; resources=${request.resources.joinToString()}"
                )
                if (!isTrustedAppUrl(request.origin)) {
                    Log.w("AdaxiomAI", "Denied WebView permission from untrusted origin ${request.origin}")
                    request.deny()
                    return@runOnUiThread
                }

                if (!request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    request.deny()
                    return@runOnUiThread
                }

                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    grantAudioCapture(request)
                } else {
                    pendingWebPermission?.deny()
                    pendingWebPermission = request
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
                }
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingWebPermission === request) pendingWebPermission = null
            Log.d("AdaxiomAI", "WebView microphone permission request was canceled")
            super.onPermissionRequestCanceled(request)
        }
    }
}
