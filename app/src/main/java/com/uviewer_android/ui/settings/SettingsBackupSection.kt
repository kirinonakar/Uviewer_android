package com.uviewer_android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uviewer_android.R
import com.uviewer_android.ui.theme.UviewerAlertDialog
import java.time.LocalDate

internal data class SettingsBackupActions(
    val busy: Boolean,
    val export: () -> Unit,
    val import: () -> Unit
)

/** Keep launchers and dialogs in the screen composition even while the section is scrolled away. */
@Composable
internal fun rememberSettingsBackupActions(viewModel: SettingsViewModel): SettingsBackupActions {
    val busy by viewModel.backupBusy.collectAsState()
    val pending by viewModel.pendingBackup.collectAsState()
    val message by viewModel.backupMessage.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let(viewModel::exportBackup)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::readBackup)
    }
    pending?.let { backup ->
        UviewerAlertDialog(
            onDismissRequest = viewModel::dismissPendingBackup,
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Text(stringResource(R.string.backup_restore_confirmation,
                    backup.favorites.size, backup.favorites.count { it.isPinned }, backup.bookmarks.size))
            },
            confirmButton = {
                TextButton(onClick = viewModel::restoreBackup, enabled = !busy) {
                    Text(stringResource(R.string.backup_import))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingBackup) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    message?.let { resource ->
        UviewerAlertDialog(
            onDismissRequest = viewModel::dismissBackupMessage,
            title = { Text(stringResource(R.string.section_backup)) },
            text = { Text(stringResource(resource)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissBackupMessage) { Text(stringResource(R.string.confirm)) }
            }
        )
    }
    return SettingsBackupActions(busy,
        export = {
            try { exportLauncher.launch("uviewer-backup-${LocalDate.now()}.json") }
            catch (_: Exception) { viewModel.reportBackupPickerError() }
        },
        import = {
            try { importLauncher.launch(arrayOf("*/*")) }
            catch (_: Exception) { viewModel.reportBackupPickerError() }
        }
    )
}

@Composable
internal fun SettingsBackupSection(actions: SettingsBackupActions, color: Color, isDark: Boolean) {
    SettingsGroup(stringResource(R.string.section_backup), color, if (isDark) 1.dp else 0.dp) {
        Text(stringResource(R.string.backup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        ListItem(
            headlineContent = { Text(stringResource(R.string.backup_export)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(enabled = !actions.busy, onClick = actions.export)
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
        ListItem(
            headlineContent = { Text(stringResource(R.string.backup_import)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(enabled = !actions.busy, onClick = actions.import)
        )
        if (actions.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}
