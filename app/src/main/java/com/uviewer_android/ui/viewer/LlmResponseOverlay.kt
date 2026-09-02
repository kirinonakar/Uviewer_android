package com.uviewer_android.ui.viewer

import android.graphics.Color.TRANSPARENT
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uviewer_android.R
import java.util.Locale

@Composable
fun LlmResponseOverlay(
    state: LlmUiState,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 680.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.llm_response_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.llm_close)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.llm_selected_text),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = when (state) {
                        is LlmUiState.Loading -> state.selectedText
                        is LlmUiState.Success -> state.selectedText
                        is LlmUiState.Error -> state.selectedText
                        LlmUiState.Idle -> ""
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (state) {
                    LlmUiState.Idle -> Unit
                    is LlmUiState.Loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                        Text(stringResource(R.string.llm_loading))
                    }

                    is LlmUiState.Success -> {
                        MarkdownResponseView(
                            markdown = state.response,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 440.dp)
                        )
                    }

                    is LlmUiState.Error -> {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp, max = 260.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (state is LlmUiState.Error) {
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.llm_close))
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.llm_close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownResponseView(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val foreground = colorScheme.onSurface.toCssColor()
    val secondary = colorScheme.onSurfaceVariant.toCssColor()
    val accent = colorScheme.primary.toCssColor()
    val codeBackground = colorScheme.surfaceVariant.toCssColor()
    val divider = colorScheme.outlineVariant.toCssColor()
    val renderedDocument = remember(
        markdown,
        foreground,
        secondary,
        accent,
        codeBackground,
        divider
    ) {
        buildMarkdownDocument(
            body = convertDocumentMarkdownToHtml(markdown),
            foreground = foreground,
            secondary = secondary,
            accent = accent,
            codeBackground = codeBackground,
            divider = divider
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = true
                setBackgroundColor(TRANSPARENT)
                webViewClient = WebViewClient()
            }
        },
        update = { webView ->
            if (webView.tag != renderedDocument) {
                webView.tag = renderedDocument
                webView.loadDataWithBaseURL(
                    null,
                    renderedDocument,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    )
}

private fun buildMarkdownDocument(
    body: String,
    foreground: String,
    secondary: String,
    accent: String,
    codeBackground: String,
    divider: String
): String {
    return """
        <!doctype html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                * { box-sizing: border-box; }
                body {
                    margin: 0;
                    padding: 0;
                    color: $foreground;
                    background: transparent;
                    font-family: sans-serif;
                    font-size: 16px;
                    line-height: 1.55;
                    overflow-wrap: anywhere;
                }
                h1, h2, h3, h4, h5, h6 {
                    color: $foreground;
                    line-height: 1.25;
                    margin: 0.9em 0 0.45em;
                }
                h1 { font-size: 1.55em; }
                h2 { font-size: 1.35em; }
                h3 { font-size: 1.18em; }
                p, ul, ol, pre, blockquote, table { margin: 0.65em 0; }
                a { color: $accent; }
                blockquote {
                    margin-left: 0;
                    padding: 0.1em 0.9em;
                    color: $secondary;
                    border-left: 4px solid $accent;
                }
                code {
                    padding: 0.12em 0.3em;
                    color: $foreground;
                    background: $codeBackground;
                    border-radius: 4px;
                    font-family: monospace;
                }
                pre {
                    padding: 0.8em;
                    overflow-x: auto;
                    color: $foreground;
                    background: $codeBackground;
                    border-radius: 8px;
                }
                pre code { padding: 0; background: transparent; }
                table {
                    display: block;
                    width: 100%;
                    overflow-x: auto;
                    border-collapse: collapse;
                }
                th, td {
                    padding: 0.45em 0.6em;
                    border: 1px solid $divider;
                    text-align: left;
                }
                th { background: $codeBackground; }
                img { max-width: 100%; height: auto; }
                hr { border: 0; border-top: 1px solid $divider; }
                mark { padding: 0.05em 0.15em; }
            </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()
}

private fun Color.toCssColor(): String {
    return String.format(Locale.US, "#%06X", toArgb() and 0xFFFFFF)
}
