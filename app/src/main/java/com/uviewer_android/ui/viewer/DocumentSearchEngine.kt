package com.uviewer_android.ui.viewer

import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.utils.LargeTextReader

internal object DocumentSearchEngine {
    suspend fun findMatches(
        query: String,
        filePath: String,
        fileType: FileEntry.FileType?,
        reader: LargeTextReader?,
        epubFlatTextLines: List<String>?
    ): List<ViewerTextSearchMatch> {
        if (fileType == FileEntry.FileType.EPUB && epubFlatTextLines != null) {
            val cleanedLines = epubFlatTextLines.map { cleanLine(it, fileType, filePath) }
            return ViewerSearchUtils.findMatchesInLines(cleanedLines, query, firstLineNumber = 1)
        }

        reader ?: return emptyList()
        val totalLines = reader.getTotalLines()
        val matches = mutableListOf<ViewerTextSearchMatch>()
        val scanSize = 1000
        var startLine = 1
        var inStyleOrScript = false
        var inYamlFrontMatter = false

        while (startLine <= totalLines) {
            val text = reader.readLines(startLine, scanSize)
            val lines = text.split('\n').map { it.trimEnd('\r') }

            val cleanedLines = mutableListOf<String>()
            var globalLineIndex = startLine
            for (line in lines) {
                var cleanLine = line
                val lower = line.lowercase()

                if (filePath.endsWith(".md", ignoreCase = true)) {
                    if (globalLineIndex == 1 && cleanLine.trim() == "---") {
                        inYamlFrontMatter = true
                        cleanLine = ""
                    } else if (inYamlFrontMatter && cleanLine.trim() == "---") {
                        inYamlFrontMatter = false
                        cleanLine = ""
                    }
                }

                if (lower.contains("<style") || lower.contains("<script")) {
                    inStyleOrScript = true
                }

                cleanLine = if (inStyleOrScript || inYamlFrontMatter) {
                    ""
                } else {
                    cleanLine(cleanLine, fileType, filePath)
                }

                if (lower.contains("</style>") || lower.contains("</script>")) {
                    inStyleOrScript = false
                }

                cleanedLines.add(cleanLine)
                globalLineIndex++
            }

            matches.addAll(ViewerSearchUtils.findMatchesInLines(cleanedLines, query, startLine))
            startLine += scanSize
        }
        return matches
    }

    private fun cleanLine(line: String, fileType: FileEntry.FileType?, filePath: String): String {
        val cleaned = when {
            fileType == FileEntry.FileType.HTML || filePath.endsWith(".html", ignoreCase = true) -> {
                line.replace(Regex("<[^>]*>"), "")
            }
            filePath.endsWith(".md", ignoreCase = true) -> {
                var clean = line.replace(Regex("<[^>]*>"), "")
                clean = clean.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                clean = clean.replace(Regex("\\[([^\\]]*)\\]\\[[^\\]]*\\]"), "$1")
                clean = clean.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                clean = clean.replace(Regex("!\\[([^\\]]*)\\]\\[[^\\]]*\\]"), "$1")

                if (clean.matches(Regex("^\\s*\\[[^\\]]+\\]:\\s*https?://\\S+.*$"))) {
                    clean = ""
                } else {
                    clean = clean.replace(Regex("^\\s*\\[\\^[^\\]]+\\]:\\s*"), "")
                }

                clean.replace(Regex("[*_#`~]"), "")
            }
            fileType == FileEntry.FileType.EPUB -> {
                line.replace(Regex("<[^>]*>"), "")
            }
            else -> {
                val noAozoraNote = line.replace(Regex("［＃[^］]*］"), "")
                val noRubyStart = noAozoraNote.replace("｜", "")
                noRubyStart.replace("《", "").replace("》", "")
            }
        }
        return cleaned
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
}
