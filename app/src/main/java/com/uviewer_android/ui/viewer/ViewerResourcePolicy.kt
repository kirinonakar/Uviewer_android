package com.uviewer_android.ui.viewer

import java.io.File
import java.net.URI

/** Only passive resources within the current book's roots are made available. */
internal class ViewerResourcePolicy(rootPaths: List<String>) {
    val roots: List<File> = rootPaths.map { File(it).canonicalFile }.distinct()

    fun resourceFile(path: String): File? = runCatching {
        val index = path.substringBefore('/').toIntOrNull() ?: return null
        val root = roots.getOrNull(index) ?: return null
        val file = File(root, path.substringAfter('/', "")).canonicalFile
        file.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile && passiveMime(it) != null }
    }.getOrNull()

    fun rewriteUrl(raw: String): String {
        if (!raw.trim().startsWith("file:", true)) return raw
        return runCatching {
            val uri = URI(raw.trim().replace(" ", "%20"))
            if (!uri.authority.isNullOrEmpty()) return@runCatching blockedUrl
            val file = File(uri.path).canonicalFile
            val assetPrefix = "/android_asset/"
            if (uri.path.startsWith(assetPrefix)) {
                return@runCatching ViewerDocumentSecurity.ORIGIN + "/assets/" + uri.rawPath.removePrefix(assetPrefix)
            }
            val index = roots.indexOfFirst { file == it || file.path.startsWith(it.path + File.separator) }
            if (index < 0) return@runCatching blockedUrl
            val relative = file.relativeTo(roots[index]).invariantSeparatorsPath.let { if (it == ".") "" else it }
            val path = URI(null, null, "/resources/$index/$relative", null).rawPath
            val directorySuffix = if (uri.path.endsWith('/') && !path.endsWith('/')) "/" else ""
            ViewerDocumentSecurity.ORIGIN + path + directorySuffix +
                (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
        }.getOrDefault(blockedUrl)
    }

    companion object {
        private val blockedUrl = ViewerDocumentSecurity.ORIGIN + "/blocked"
        fun passiveMime(file: File): String? = when (file.extension.lowercase()) {
            "css" -> "text/css"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            else -> null
        }
    }
}
