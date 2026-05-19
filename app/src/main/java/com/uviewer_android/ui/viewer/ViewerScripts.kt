package com.uviewer_android.ui.viewer

object ViewerScripts {
    fun getScrollLogic(
        isVertical: Boolean,
        enableAutoLoading: Boolean,
        targetLine: Int,
        totalLines: Int,
        linePrefix: String,
        isImageOnly: Boolean = false
    ): String {
        return ViewerScrollScripts.getScrollLogic(
            isVertical = isVertical,
            enableAutoLoading = enableAutoLoading,
            targetLine = targetLine,
            totalLines = totalLines,
            linePrefix = linePrefix,
            isImageOnly = isImageOnly
        )
    }

    fun getStyleSheet(
        isVertical: Boolean,
        bgColor: String,
        textColor: String,
        fontFamily: String,
        fontSize: Int,
        sideMargin: Int
    ): String {
        return ViewerStyleScripts.getStyleSheet(
            isVertical = isVertical,
            bgColor = bgColor,
            textColor = textColor,
            fontFamily = fontFamily,
            fontSize = fontSize,
            sideMargin = sideMargin
        )
    }
}
