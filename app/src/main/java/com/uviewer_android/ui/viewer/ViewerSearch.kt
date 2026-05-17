package com.uviewer_android.ui.viewer

import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uviewer_android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class ViewerTextSearchMatch(
    val line: Int,
    val occurrenceInLine: Int
)

data class ViewerTextSearchState(
    val query: String = "",
    val matches: List<ViewerTextSearchMatch> = emptyList(),
    val currentIndex: Int = -1,
    val isSearching: Boolean = false
) {
    val currentMatch: ViewerTextSearchMatch?
        get() = matches.getOrNull(currentIndex)
}

data class PdfSearchMatch(
    val pageIndex: Int,
    val bounds: List<RectF>
)

data class PdfSearchState(
    val query: String = "",
    val matches: List<PdfSearchMatch> = emptyList(),
    val currentIndex: Int = -1,
    val isSearching: Boolean = false,
    val isSupported: Boolean = true
) {
    val currentMatch: PdfSearchMatch?
        get() = matches.getOrNull(currentIndex)
}

object ViewerSearchUtils {
    fun findMatchesInLines(
        lines: List<String>,
        query: String,
        firstLineNumber: Int
    ): List<ViewerTextSearchMatch> {
        if (query.isBlank()) return emptyList()
        val matches = mutableListOf<ViewerTextSearchMatch>()
        lines.forEachIndexed { lineIndex, line ->
            var fromIndex = 0
            var occurrence = 0
            while (fromIndex <= line.length) {
                val index = line.indexOf(query, startIndex = fromIndex, ignoreCase = true)
                if (index < 0) break
                matches.add(ViewerTextSearchMatch(firstLineNumber + lineIndex, occurrence))
                occurrence++
                fromIndex = index + query.length.coerceAtLeast(1)
            }
        }
        return matches
    }

    fun nextIndex(currentIndex: Int, size: Int): Int {
        if (size <= 0) return -1
        return if (currentIndex < 0) 0 else (currentIndex + 1) % size
    }

    fun previousIndex(currentIndex: Int, size: Int): Int {
        if (size <= 0) return -1
        return if (currentIndex < 0) size - 1 else (currentIndex - 1 + size) % size
    }
}

object ViewerSearchScripts {
    fun highlightDocument(query: String, target: ViewerTextSearchMatch?): String {
        val queryJson = JSONObject.quote(query)
        val line = target?.line ?: -1
        val occurrence = target?.occurrenceInLine ?: -1
        return """
            (function() {
                var query = $queryJson;
                var targetLine = $line;
                var targetOccurrence = $occurrence;
                var styleId = 'uv-search-style';
                var oldStyle = document.getElementById(styleId);
                if (!oldStyle) {
                    oldStyle = document.createElement('style');
                    oldStyle.id = styleId;
                    oldStyle.textContent =
                        '.uv-search-highlight{background:rgba(255,221,87,.72)!important;color:inherit!important;border-radius:.12em;padding:0 .08em;}' +
                        '.uv-search-current{background:rgba(255,145,77,.9)!important;outline:2px solid rgba(255,115,0,.55);}';
                    document.head.appendChild(oldStyle);
                }

                document.querySelectorAll('.uv-search-highlight').forEach(function(node) {
                    node.replaceWith(document.createTextNode(node.textContent || ''));
                });
                document.body.normalize();
                if (!query) return;

                var escaped = query.replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\${'$'}&');
                var regex = new RegExp(escaped, 'gi');
                var lineCounts = {};
                var currentNode = null;
                var walker = document.createTreeWalker(
                    document.body,
                    NodeFilter.SHOW_TEXT,
                    {
                        acceptNode: function(node) {
                            if (!node.nodeValue || !regex.test(node.nodeValue)) {
                                regex.lastIndex = 0;
                                return NodeFilter.FILTER_REJECT;
                            }
                            regex.lastIndex = 0;
                            var parent = node.parentElement;
                            if (!parent) return NodeFilter.FILTER_REJECT;
                            if (parent.closest('script,style,textarea,input,.uv-search-highlight')) {
                                return NodeFilter.FILTER_REJECT;
                            }
                            return NodeFilter.FILTER_ACCEPT;
                        }
                    }
                );

                var nodes = [];
                while (walker.nextNode()) nodes.push(walker.currentNode);

                nodes.forEach(function(textNode) {
                    var text = textNode.nodeValue || '';
                    var fragment = document.createDocumentFragment();
                    var lastIndex = 0;
                    regex.lastIndex = 0;
                    var match;
                    while ((match = regex.exec(text)) !== null) {
                        if (match.index > lastIndex) {
                            fragment.appendChild(document.createTextNode(text.substring(lastIndex, match.index)));
                        }

                        var span = document.createElement('span');
                        span.className = 'uv-search-highlight';
                        span.textContent = match[0];

                        var lineElement = textNode.parentElement ? textNode.parentElement.closest('[id^="line-"]') : null;
                        if (lineElement) {
                            var lineNumber = parseInt(lineElement.id.replace('line-', ''), 10);
                            var count = lineCounts[lineNumber] || 0;
                            lineCounts[lineNumber] = count + 1;
                            if (lineNumber === targetLine && count === targetOccurrence) {
                                span.className += ' uv-search-current';
                                currentNode = span;
                            }
                        }
                        fragment.appendChild(span);
                        lastIndex = match.index + match[0].length;
                    }
                    if (lastIndex < text.length) {
                        fragment.appendChild(document.createTextNode(text.substring(lastIndex)));
                    }
                    textNode.replaceWith(fragment);
                });

                if (currentNode) {
                    currentNode.scrollIntoView({ behavior: 'instant', block: 'center', inline: 'center' });
                }
            })();
        """.trimIndent()
    }
}

object PdfSearchEngine {
    suspend fun search(
        renderer: PdfRenderer?,
        pageCount: Int,
        query: String,
        localFilePath: String?
    ): PdfSearchState {
        if (query.isBlank()) return PdfSearchState(query = query)

        val matches = withContext(Dispatchers.IO) {
            searchWithPlatformRenderer(renderer, pageCount, query)
                ?: searchWithExtensionRenderer(localFilePath, pageCount, query)
        }

        if (matches == null) {
            return PdfSearchState(query = query, isSupported = false)
        }
        return PdfSearchState(
            query = query,
            matches = matches,
            currentIndex = if (matches.isEmpty()) -1 else 0
        )
    }

    private fun searchWithPlatformRenderer(
        renderer: PdfRenderer?,
        pageCount: Int,
        query: String
    ): List<PdfSearchMatch>? {
        if (renderer == null || Build.VERSION.SDK_INT < 35) return null

        return try {
            val result = mutableListOf<PdfSearchMatch>()
            for (pageIndex in 0 until pageCount) {
                val pageMatches = synchronized(renderer) {
                    renderer.openPage(pageIndex).use { page ->
                        val nativeMatches = page.searchText(query).map { match ->
                            PdfSearchMatch(pageIndex, match.bounds)
                        }
                        nativeMatches.ifEmpty {
                            findTextContentMatches(
                                page = page,
                                pageIndex = pageIndex,
                                query = query,
                                fallbackBounds = RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
                            )
                        }
                    }
                }
                result.addAll(pageMatches)
            }
            result
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun searchWithExtensionRenderer(
        localFilePath: String?,
        pageCount: Int,
        query: String
    ): List<PdfSearchMatch>? {
        val path = localFilePath ?: return null
        val file = File(path)
        if (!file.exists()) return null

        return try {
            val rendererClass = Class.forName("android.graphics.pdf.PdfRendererPreV")
            val rendererConstructor = rendererClass.getConstructor(ParcelFileDescriptor::class.java)
            val openPage = rendererClass.getMethod("openPage", Int::class.javaPrimitiveType)
            val closeRenderer = rendererClass.getMethod("close")

            val pageClass = Class.forName("android.graphics.pdf.PdfRendererPreV\$Page")
            val searchText = pageClass.getMethod("searchText", String::class.java)
            val getTextContents = pageClass.getMethod("getTextContents")
            val getWidth = pageClass.getMethod("getWidth")
            val getHeight = pageClass.getMethod("getHeight")
            val closePage = pageClass.getMethod("close")

            val matchClass = Class.forName("android.graphics.pdf.models.PageMatchBounds")
            val getBounds = matchClass.getMethod("getBounds")

            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val extensionRenderer = rendererConstructor.newInstance(fd)
            val result = mutableListOf<PdfSearchMatch>()

            try {
                for (pageIndex in 0 until pageCount) {
                    val page = openPage.invoke(extensionRenderer, pageIndex) ?: continue
                    try {
                        val pageMatches = searchText.invoke(page, query) as List<Any>
                        if (pageMatches.isNotEmpty()) {
                            pageMatches.forEach { match ->
                                val bounds = getBounds.invoke(match) as List<RectF>
                                result.add(PdfSearchMatch(pageIndex, bounds))
                            }
                        } else {
                            val width = (getWidth.invoke(page) as Int).toFloat()
                            val height = (getHeight.invoke(page) as Int).toFloat()
                            result.addAll(
                                findTextContentMatches(
                                    page = page,
                                    pageIndex = pageIndex,
                                    query = query,
                                    fallbackBounds = RectF(0f, 0f, width, height),
                                    getTextContentsMethod = getTextContents
                                )
                            )
                        }
                    } finally {
                        closePage.invoke(page)
                    }
                }
            } finally {
                closeRenderer.invoke(extensionRenderer)
            }
            result
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findTextContentMatches(
        page: Any,
        pageIndex: Int,
        query: String,
        fallbackBounds: RectF,
        getTextContentsMethod: java.lang.reflect.Method? = null
    ): List<PdfSearchMatch> {
        return try {
            val method = getTextContentsMethod ?: page.javaClass.getMethod("getTextContents")
            val textContents = method.invoke(page) as List<Any>
            if (textContents.isEmpty()) return emptyList()

            val firstContent = textContents.first()
            val contentClass = firstContent.javaClass
            val getText = contentClass.getMethod("getText")
            val getBounds = contentClass.getMethod("getBounds")
            val searchableText = StringBuilder()
            val boundsBySearchChar = mutableListOf<List<RectF>>()

            textContents.forEach { content ->
                val text = getText.invoke(content) as? String ?: return@forEach
                val contentBounds = (getBounds.invoke(content) as? List<RectF>)
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf(fallbackBounds)

                text.forEachIndexed { index, char ->
                    if (!isPdfSearchIgnored(char)) {
                        searchableText.append(char.lowercaseChar())
                        boundsBySearchChar.add(
                            if (contentBounds.size == text.length) listOf(contentBounds[index])
                            else contentBounds
                        )
                    }
                }
            }

            val normalizedQuery = normalizePdfSearchText(query)
            if (normalizedQuery.isEmpty()) return emptyList()

            val result = mutableListOf<PdfSearchMatch>()
            var fromIndex = 0
            val haystack = searchableText.toString()
            while (fromIndex <= haystack.length) {
                val index = haystack.indexOf(normalizedQuery, startIndex = fromIndex)
                if (index < 0) break

                val matchBounds = boundsBySearchChar
                    .subList(index, index + normalizedQuery.length)
                    .flatten()
                    .distinctBy { rect ->
                        "${rect.left}:${rect.top}:${rect.right}:${rect.bottom}"
                    }
                    .ifEmpty { listOf(fallbackBounds) }

                result.add(PdfSearchMatch(pageIndex, matchBounds))
                fromIndex = index + normalizedQuery.length.coerceAtLeast(1)
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun normalizePdfSearchText(text: String): String {
        return buildString {
            text.forEach { char ->
                if (!isPdfSearchIgnored(char)) append(char.lowercaseChar())
            }
        }
    }

    private fun isPdfSearchIgnored(char: Char): Boolean {
        val type = Character.getType(char)
        return char.isWhitespace() ||
            type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerSearchBar(
    query: String,
    matchText: String,
    isSearching: Boolean,
    isSupported: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp)
            )
            Text(
                text = when {
                    !isSupported -> stringResource(R.string.search_pdf_unsupported)
                    isSearching -> stringResource(R.string.searching)
                    else -> matchText
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isSupported) LocalContentColor.current else MaterialTheme.colorScheme.error
            )
            IconButton(onClick = onPrevious, enabled = isSupported && !isSearching, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.search_previous))
            }
            IconButton(onClick = onNext, enabled = isSupported && !isSearching, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.search_next))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.close_search))
            }
        }
    }
}

@Composable
fun SearchDropdownMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.search)) },
        onClick = onClick,
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        }
    )
}

@Composable
fun PdfSearchHighlightOverlay(
    matches: List<PdfSearchMatch>,
    currentMatch: PdfSearchMatch?,
    pageWidth: Int,
    pageHeight: Int,
    modifier: Modifier = Modifier
) {
    if (matches.isEmpty() || pageWidth <= 0 || pageHeight <= 0) return

    Canvas(modifier = modifier) {
        val scaleX = size.width / pageWidth.toFloat()
        val scaleY = size.height / pageHeight.toFloat()
        matches.forEach { match ->
            val isCurrent = match == currentMatch
            val fillColor = if (isCurrent) Color(0x99FF914D) else Color(0x80FFDD57)
            val strokeColor = if (isCurrent) Color(0xFFFF7300) else Color.Transparent
            match.bounds.forEach { rect ->
                val left = rect.left * scaleX
                val top = rect.top * scaleY
                val width = (rect.right - rect.left) * scaleX
                val height = (rect.bottom - rect.top) * scaleY
                drawRect(
                    color = fillColor,
                    topLeft = Offset(left, top),
                    size = Size(width, height)
                )
                if (isCurrent) {
                    drawRect(
                        color = strokeColor,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }
    }
}
