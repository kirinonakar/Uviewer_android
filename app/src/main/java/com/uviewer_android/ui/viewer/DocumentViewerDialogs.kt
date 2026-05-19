package com.uviewer_android.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uviewer_android.R

@Composable
fun DocumentEncodingDialog(
    uiState: DocumentViewerUiState,
    onSelectEncoding: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    com.uviewer_android.ui.theme.UviewerAlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.select_encoding), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val encodings = listOf(
                    stringResource(R.string.encoding_auto) to null,
                    "UTF-8" to "UTF-8",
                    stringResource(R.string.encoding_sjis) to "Shift_JIS",
                    stringResource(R.string.encoding_euckr) to "EUC-KR",
                    stringResource(R.string.encoding_johab) to "JO-HAB",
                    stringResource(R.string.encoding_w1252) to "windows-1252"
                )
                encodings.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectEncoding(value)
                                onDismiss()
                            }
                            .padding(vertical = 4.dp, horizontal = 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = uiState.manualEncoding == value, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(20.dp)
            ) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun DocumentLoadingOverlay(uiState: DocumentViewerUiState) {
    if (!uiState.isLoading) return

    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                if (uiState.loadProgress < 1f) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Indexing: ${(uiState.loadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun DocumentGoToLineDialog(
    currentLine: Int,
    totalLines: Int,
    onDismiss: () -> Unit,
    onGoToLine: (Int) -> Unit
) {
    var targetLineStr by remember { mutableStateOf(currentLine.toString()) }
    com.uviewer_android.ui.theme.UviewerAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to Line") },
        text = {
            Column {
                Text("Enter line number (1 - $totalLines)")
                OutlinedTextField(
                    value = targetLineStr,
                    onValueChange = { if (it.all { c -> c.isDigit() }) targetLineStr = it },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val line = targetLineStr.toIntOrNull()
                if (line != null && line in 1..totalLines) {
                    onGoToLine(line)
                    onDismiss()
                }
            }) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
