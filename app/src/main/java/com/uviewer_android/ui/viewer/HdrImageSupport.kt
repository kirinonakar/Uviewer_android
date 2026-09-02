package com.uviewer_android.ui.viewer

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.Window

/**
 * Returns true when the bitmap contains pixels that must not be passed through an 8-bit
 * transformation. Ultra HDR images carry their extra range in a gainmap; other HDR-capable
 * images are commonly decoded into one of Android's high-precision bitmap configurations.
 */
internal fun Bitmap.containsHdrContent(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasGainmap()) {
        return true
    }

    if (config == Bitmap.Config.RGBA_F16) {
        return true
    }

    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        config == Bitmap.Config.RGBA_1010102
}

/**
 * Enable HDR output only while an HDR image is visible. Android's UI toolkit officially supports
 * Ultra HDR rendering from API 34; older versions keep the normal SDR window mode.
 */
internal fun Window.setHdrColorMode(enabled: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return
    }

    val requestedMode = if (enabled) {
        ActivityInfo.COLOR_MODE_HDR
    } else {
        ActivityInfo.COLOR_MODE_DEFAULT
    }

    if (colorMode != requestedMode) {
        setColorMode(requestedMode)
    }
}
