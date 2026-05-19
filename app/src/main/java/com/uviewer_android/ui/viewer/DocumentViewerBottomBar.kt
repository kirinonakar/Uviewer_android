package com.uviewer_android.ui.viewer

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uviewer_android.data.model.FileEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerBottomBar(
    uiState: DocumentViewerUiState,
    type: FileEntry.FileType,
    currentLine: Int,
    tempSliderValue: Float,
    sliderInteractionSource: MutableInteractionSource,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (!uiState.isLoading) {
            Text(
                uiState.fileName ?: "",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        val isEpub = type == FileEntry.FileType.EPUB
        val isEpubFlat = isEpub
        val totalCh = uiState.epubChapters.size.coerceAtLeast(1)

        val sliderValue = if (isEpub && !isEpubFlat) {
            val chIdx = uiState.currentChapterIndex
            val chLines = uiState.totalLines.coerceAtLeast(1)
            chIdx.toFloat() + (currentLine.toFloat() / chLines).coerceIn(0f, 1f)
        } else {
            currentLine.toFloat()
        }

        val sliderRange = if (isEpub && !isEpubFlat) {
            0f..totalCh.toFloat()
        } else {
            1f..uiState.totalLines.toFloat().coerceAtLeast(1f)
        }

        val progressPercent = if (isEpub && !isEpubFlat) {
            ((sliderValue / totalCh) * 100).toInt().coerceIn(0, 100)
        } else {
            if (uiState.totalLines > 0) (currentLine * 100 / uiState.totalLines).coerceIn(0, 100) else 0
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (isEpub) {
                    val ch = uiState.currentChapterIndex + 1
                    "Ch: $ch / $totalCh | Line: $currentLine"
                } else {
                    "Line: $currentLine / ${uiState.totalLines}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "$progressPercent%",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }

        val displayValue = if (tempSliderValue >= 0f) tempSliderValue else sliderValue

        Slider(
            value = displayValue,
            onValueChange = onSliderValueChange,
            onValueChangeFinished = onSliderValueChangeFinished,
            valueRange = sliderRange,
            interactionSource = sliderInteractionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = remember { MutableInteractionSource() },
                    thumbSize = androidx.compose.ui.unit.DpSize(16.dp, 16.dp),
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().height(32.dp)
        )
    }
}
