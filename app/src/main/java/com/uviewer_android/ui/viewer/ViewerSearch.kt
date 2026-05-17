package com.uviewer_android.ui.viewer

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uviewer_android.R
import org.json.JSONObject

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
                    var rect = currentNode.getBoundingClientRect();
                    var deltaX = rect.left + rect.width / 2 - window.innerWidth / 2;
                    var deltaY = rect.top + rect.height / 2 - window.innerHeight / 2;
                    if (typeof window.safeScrollBy === 'function') {
                        window.safeScrollBy(deltaX, deltaY);
                    } else {
                        window.scrollBy(deltaX, deltaY);
                    }
                }
            })();
        """.trimIndent()
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
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
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
