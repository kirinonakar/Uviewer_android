package com.uviewer_android.ui.viewer

import android.view.Window
import android.webkit.WebSettings

@Suppress("DEPRECATION")
internal fun Window.setTransparentSystemBarColors() {
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
}

@Suppress("DEPRECATION")
internal fun WebSettings.allowViewerFileUrlAccess() {
    allowFileAccessFromFileURLs = true
    allowUniversalAccessFromFileURLs = true
}
