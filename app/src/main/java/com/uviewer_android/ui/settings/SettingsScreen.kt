package com.uviewer_android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.data.WebDavServer
import com.uviewer_android.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val servers by viewModel.servers.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val fontFamily by viewModel.fontFamily.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.title_settings)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_server_desc))
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                Text(
                    text = stringResource(R.string.section_appearance),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            item {
                val themeLabel = when (themeMode) {
                    "light" -> stringResource(R.string.theme_light)
                    "dark" -> stringResource(R.string.theme_dark)
                    else -> stringResource(R.string.theme_system)
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.theme)) },
                    supportingContent = { Text(themeLabel) },
                    modifier = Modifier.clickable { showThemeDialog = true }
                )
            }
            item { HorizontalDivider() }
            item {
                Text(
                    text = stringResource(R.string.section_reading),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.font_size_fmt, fontSize))
                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { viewModel.setFontSize(it.toInt()) },
                        valueRange = 12f..36f,
                        steps = 24
                    )
                }
            }
            item {
               val fontLabel = when(fontFamily) {
                   "serif" -> stringResource(R.string.font_serif)
                   "sans-serif" -> stringResource(R.string.font_sans_serif)
                   "monospace" -> stringResource(R.string.font_monospace)
                   else -> stringResource(R.string.font_default)
               }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.font_family)) },
                    supportingContent = { Text(fontLabel) },
                    modifier = Modifier.clickable { showFontDialog = true }
                )
            }
            item { HorizontalDivider() }
            item {
                Text(
                    text = stringResource(R.string.section_webdav),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            items(servers) { server ->
                ServerItemRow(
                    server = server,
                    onDelete = { viewModel.deleteServer(server) }
                )
            }
        }

        if (showAddDialog) {
            AddServerDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { name, url, user, pass ->
                    viewModel.addServer(name, url, user, pass)
                    showAddDialog = false
                }
            )
        }
        
        if (showThemeDialog) {
            ThemeSelectionDialog(
                currentMode = themeMode,
                onDismiss = { showThemeDialog = false },
                onSelect = { mode ->
                    viewModel.setThemeMode(mode)
                    showThemeDialog = false
                }
            )
        }

        if (showFontDialog) {
            FontSelectionDialog(
                currentFamily = fontFamily,
                onDismiss = { showFontDialog = false },
                onSelect = { family ->
                    viewModel.setFontFamily(family)
                    showFontDialog = false
                }
            )
        }
    }
}

@Composable
fun FontSelectionDialog(
    currentFamily: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_font_family)) },
        text = {
            Column {
                ThemeOptionRow(stringResource(R.string.font_serif), "serif", currentFamily, onSelect)
                ThemeOptionRow(stringResource(R.string.font_sans_serif), "sans-serif", currentFamily, onSelect)
                ThemeOptionRow(stringResource(R.string.font_monospace), "monospace", currentFamily, onSelect)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ThemeSelectionDialog(
    currentMode: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_theme)) },
        text = {
            Column {
                ThemeOptionRow(stringResource(R.string.theme_system), "system", currentMode, onSelect)
                ThemeOptionRow(stringResource(R.string.theme_light), "light", currentMode, onSelect)
                ThemeOptionRow(stringResource(R.string.theme_dark), "dark", currentMode, onSelect)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ThemeOptionRow(
    label: String,
    mode: String,
    currentMode: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(mode) }
            .padding(vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        RadioButton(
            selected = (mode == currentMode),
            onClick = null // Handled by Row clickable
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label)
    }
}

@Composable
fun ServerItemRow(
    server: WebDavServer,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = { Text(server.name) },
        supportingContent = { Text(server.url) },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    )
}

@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_webdav_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.server_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.server_url)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, url, username, password) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
