package com.uviewer_android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import com.uviewer_android.data.repository.UserPreferencesRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
    val docBackgroundColor by viewModel.docBackgroundColor.collectAsState()
    val language by viewModel.language.collectAsState()
    val invertImageControl by viewModel.invertImageControl.collectAsState()
    val dualPageOrder by viewModel.dualPageOrder.collectAsState()
    val persistZoom by viewModel.persistZoom.collectAsState()
    val sharpeningAmount by viewModel.sharpeningAmount.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showDocBgDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDualPageOrderDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.title_settings)) })
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item { HorizontalDivider() }
            item {
                Text(
                    text = stringResource(R.string.section_appearance),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            item {
                val themeLabel = when (themeMode) {
                    UserPreferencesRepository.THEME_LIGHT -> stringResource(R.string.theme_light)
                    UserPreferencesRepository.THEME_DARK -> stringResource(R.string.theme_dark)
                    else -> stringResource(R.string.theme_system)
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.theme)) },
                    supportingContent = { Text(themeLabel) },
                    modifier = Modifier.clickable { showThemeDialog = true }
                )
            }
            item {
                val languageLabel = when (language) {
                    UserPreferencesRepository.LANG_EN -> stringResource(R.string.lang_en)
                    UserPreferencesRepository.LANG_KO -> stringResource(R.string.lang_ko)
                    UserPreferencesRepository.LANG_JA -> stringResource(R.string.lang_ja)
                    else -> stringResource(R.string.lang_system)
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.language)) },
                    supportingContent = { Text(languageLabel) },
                    modifier = Modifier.clickable { showLanguageDialog = true }
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
                val docBgLabel = when (docBackgroundColor) {
                    UserPreferencesRepository.DOC_BG_WHITE -> stringResource(R.string.doc_bg_white)
                    UserPreferencesRepository.DOC_BG_SEPIA -> stringResource(R.string.doc_bg_sepia)
                    UserPreferencesRepository.DOC_BG_DARK -> stringResource(R.string.doc_bg_dark)
                    else -> stringResource(R.string.doc_bg_white)
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.document_background)) },
                    supportingContent = { Text(docBgLabel) },
                    modifier = Modifier.clickable { showDocBgDialog = true }
                )

                var showCustomColorDialog by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text("Manual Color Picker") },
                    supportingContent = { Text("Set hex colors for background and text") },
                    trailingContent = { Icon(Icons.Default.Palette, contentDescription = null) },
                    modifier = Modifier.clickable { showCustomColorDialog = true }
                )
                
                if (showCustomColorDialog) {
                    val docTextColor by viewModel.docTextColor.collectAsState()
                    var bgHex by remember { mutableStateOf(docBackgroundColor) }
                    var textHex by remember { mutableStateOf(docTextColor) }
                    
                    AlertDialog(
                        onDismissRequest = { showCustomColorDialog = false },
                        title = { Text("Manual Color Picker") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = bgHex,
                                    onValueChange = { bgHex = it },
                                    label = { Text("Background Color (Hex)") },
                                    placeholder = { Text("#FFFFFF") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = textHex,
                                    onValueChange = { textHex = it },
                                    label = { Text("Text Color (Hex)") },
                                    placeholder = { Text("#000000") },
                                    singleLine = true
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("Preview:", style = MaterialTheme.typography.labelMedium)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(60.dp)
                                        .background(
                                            try { Color(android.graphics.Color.parseColor(if (bgHex.startsWith("#")) bgHex else "#$bgHex")) } catch (e: Exception) { Color.White },
                                            shape = MaterialTheme.shapes.small
                                        )
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Sample Text",
                                        color = try { Color(android.graphics.Color.parseColor(if (textHex.startsWith("#")) textHex else "#$textHex")) } catch (e: Exception) { Color.Black }
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.setDocBackgroundColor(if (bgHex.startsWith("#")) bgHex else "#$bgHex")
                                viewModel.setDocTextColor(if (textHex.startsWith("#")) textHex else "#$textHex")
                                showCustomColorDialog = false
                            }) { Text(stringResource(R.string.confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCustomColorDialog = false }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
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
                    text = stringResource(R.string.section_image_viewer),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.persist_zoom)) },
                    supportingContent = { Text(stringResource(R.string.persist_zoom_desc)) },
                    trailingContent = {
                        Switch(
                            checked = persistZoom,
                            onCheckedChange = { viewModel.setPersistZoom(it) }
                        )
                    }
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.sharpening_amount_fmt, sharpeningAmount))
                    Slider(
                        value = sharpeningAmount.toFloat(),
                        onValueChange = { viewModel.setSharpeningAmount(it.toInt()) },
                        valueRange = 0f..10f,
                        steps = 9
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.invert_image_control)) },
                    supportingContent = { Text(stringResource(R.string.invert_image_control_desc)) },
                    trailingContent = {
                        Switch(
                            checked = invertImageControl,
                            onCheckedChange = { viewModel.setInvertImageControl(it) }
                        )
                    }
                )
            }
            item {
                val orderLabel = if (dualPageOrder == 0) stringResource(R.string.dual_page_ltr) else stringResource(R.string.dual_page_rtl)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.dual_page_order)) },
                    supportingContent = { Text(orderLabel) },
                    modifier = Modifier.clickable { showDualPageOrderDialog = true }
                )
            }
            item { HorizontalDivider() }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.section_webdav), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.add_server_menu), color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { showAddDialog = true }
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

        if (showDocBgDialog) {
            DocBgSelectionDialog(
                currentBg = docBackgroundColor,
                onDismiss = { showDocBgDialog = false },
                onSelect = { bg ->
                    viewModel.setDocBackgroundColor(bg)
                    showDocBgDialog = false
                }
            )
        }

        if (showLanguageDialog) {
            LanguageSelectionDialog(
                currentLanguage = language,
                onDismiss = { showLanguageDialog = false },
                onSelect = { lang ->
                    viewModel.setLanguage(lang)
                    showLanguageDialog = false
                }
            )
        }

        if (showDualPageOrderDialog) {
            DualPageOrderSelectionDialog(
                currentOrder = dualPageOrder,
                onDismiss = { showDualPageOrderDialog = false },
                onSelect = { order ->
                    viewModel.setDualPageOrder(order)
                    showDualPageOrderDialog = false
                }
            )
        }
    }
}

@Composable
fun DualPageOrderSelectionDialog(
    currentOrder: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_dual_page_order)) },
        text = {
            Column {
                ThemeOptionRow(stringResource(R.string.dual_page_ltr), "0", currentOrder.toString(), { onSelect(0) })
                ThemeOptionRow(stringResource(R.string.dual_page_rtl), "1", currentOrder.toString(), { onSelect(1) })
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
fun LanguageSelectionDialog(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_language)) },
        text = {
            Column {
                ThemeOptionRow(stringResource(R.string.lang_system), UserPreferencesRepository.LANG_SYSTEM, currentLanguage, onSelect)
                ThemeOptionRow(stringResource(R.string.lang_en), UserPreferencesRepository.LANG_EN, currentLanguage, onSelect)
                ThemeOptionRow(stringResource(R.string.lang_ko), UserPreferencesRepository.LANG_KO, currentLanguage, onSelect)
                ThemeOptionRow(stringResource(R.string.lang_ja), UserPreferencesRepository.LANG_JA, currentLanguage, onSelect)
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
fun DocBgSelectionDialog(
    currentBg: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_doc_bg)) },
        text = {
            Column {
                ThemeOptionRow(stringResource(R.string.doc_bg_white), UserPreferencesRepository.DOC_BG_WHITE, currentBg, onSelect)
                ThemeOptionRow(stringResource(R.string.doc_bg_sepia), UserPreferencesRepository.DOC_BG_SEPIA, currentBg, onSelect)
                ThemeOptionRow(stringResource(R.string.doc_bg_dark), UserPreferencesRepository.DOC_BG_DARK, currentBg, onSelect)
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
                ThemeOptionRow(stringResource(R.string.theme_system), UserPreferencesRepository.THEME_SYSTEM, currentMode, onSelect)
                ThemeOptionRow(stringResource(R.string.theme_light), UserPreferencesRepository.THEME_LIGHT, currentMode, onSelect)
                ThemeOptionRow(stringResource(R.string.theme_dark), UserPreferencesRepository.THEME_DARK, currentMode, onSelect)
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
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(server.name, maxLines = 1) },
        supportingContent = { Text(server.url, maxLines = 1) },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete, 
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error
                )
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
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var useHttps by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_webdav_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.server_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Use HTTPS", modifier = Modifier.weight(1f))
                    Switch(checked = useHttps, onCheckedChange = { useHttps = it })
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host (e.g. example.com/dav)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { char -> char.isDigit() } },
                    label = { Text(stringResource(R.string.server_port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { 
                    val protocol = if (useHttps) "https://" else "http://"
                    val portPart = if (port.isNotEmpty()) ":$port" else ""
                    val cleanHost = host.removePrefix("http://").removePrefix("https://").trim('/')
                    val fullUrl = "$protocol$cleanHost$portPart"
                    onAdd(name, fullUrl, username, password) 
                },
                enabled = name.isNotBlank() && host.isNotBlank()
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
