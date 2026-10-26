package com.uviewer_android.ui.viewer

import android.view.Window
import android.webkit.WebSettings

@Suppress("DEPRECATION")
internal fun Window.setTransparentSystemBarColors() {
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
}

@Suppress("DEPRECATION")
internal fun WebSettings.restrictViewerFileUrlAccess() {
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
}

internal fun WebSettings.configureViewerSecurity() {
    restrictViewerFileUrlAccess()
    allowFileAccess = false
    allowContentAccess = false
    javaScriptEnabled = true // Only bundled rendering scripts are admitted by the document policy.
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)
    domStorageEnabled = false
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    safeBrowsingEnabled = true
}
