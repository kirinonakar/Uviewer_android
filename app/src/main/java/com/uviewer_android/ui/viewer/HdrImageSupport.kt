package com.uviewer_android.ui.viewer

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.Window

private val HIGH_PRECISION_IMAGE_EXTENSIONS = setOf("avif", "heif", "heic")

/**
 * AVIF/HEIF containers can carry 10-bit PQ/HLG content even when they do not have an Ultra HDR
 * gainmap. Prefer a high-precision decode for these containers so the decoder does not choose an
 * 8-bit ARGB_8888 bitmap before we can inspect the result.
 */
internal fun String.isHighPrecisionImageSource(): Boolean {
    val sourceWithoutFragment = substringBefore('#')
    val pathName = sourceWithoutFragment
        .substringBefore('?')
        .substringAfterLast('/')
        .substringAfterLast('\\')

    fun hasHighPrecisionExtension(name: String): Boolean {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
            .substringBefore('&')
            .lowercase()
        return extension in HIGH_PRECISION_IMAGE_EXTENSIONS
    }

    if (hasHighPrecisionExtension(pathName)) {
        return true
    }

    // Remote archive fetchers keep the entry name in a query parameter, e.g. entry=page.avif.
    return sourceWithoutFragment
        .substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .asSequence()
        .map { it.substringAfter('=', missingDelimiterValue = it) }
        .any(::hasHighPrecisionExtension)
}

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
 * Enable HDR output only while an HDR image is visible. The window color mode is available from
 * API 26; devices without an HDR display fall back to their normal output mode.
 */
internal fun Window.setHdrColorMode(enabled: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
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
