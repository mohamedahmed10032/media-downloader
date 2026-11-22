package com.melmeligy.mediadownloader.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.AudioBitrate
import com.melmeligy.mediadownloader.domain.model.ThemeMode
import com.melmeligy.mediadownloader.domain.model.VideoQuality
import com.melmeligy.mediadownloader.domain.model.formatTreeUriForDisplay
import com.melmeligy.mediadownloader.ui.theme.Indigo
import com.melmeligy.mediadownloader.ui.theme.Teal
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val customFolderStatus by viewModel.customFolderStatus.collectAsStateWithLifecycle()
    var showSaveLocationSheet by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onCustomFolderSelected(uri) { isWritable ->
                val readable = formatTreeUriForDisplay(uri.toString())
                val msg = if (isWritable) {
                    "Save folder set to $readable"
                } else {
                    "Selected folder is read-only. Please pick another folder."
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshCustomFolderStatus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionHeader("Downloads")

        DropdownSetting(
            title = stringResource(R.string.settings_default_quality),
            current = settings.defaultVideoQuality,
            options = VideoQuality.entries,
            label = { it.label },
            onSelect = { viewModel.setVideoQuality(it) }
        )
        DropdownSetting(
            title = stringResource(R.string.settings_default_audio_bitrate),
            current = settings.defaultAudioBitrate,
            options = AudioBitrate.entries,
            label = { it.label },
            onSelect = { viewModel.setAudioBitrate(it) }
        )
        SliderSetting(
            title = stringResource(R.string.settings_max_concurrent),
            value = settings.maxConcurrentDownloads,
            range = AppSettings.MIN_CONCURRENT..AppSettings.MAX_CONCURRENT,
            onChange = { viewModel.setMaxConcurrent(it) }
        )
        SliderSetting(
            title = stringResource(R.string.settings_retry_count),
            value = settings.retryCount,
            range = AppSettings.MIN_RETRIES..AppSettings.MAX_RETRIES,
            valueLabel = { count -> if (count == 0) "Off (0)" else count.toString() },
            onChange = { viewModel.setRetryCount(it) }
        )
        ClickableSetting(
            title = stringResource(R.string.settings_save_location),
            value = settings.saveLocationSummary(),
            onClick = {
                viewModel.refreshCustomFolderStatus()
                showSaveLocationSheet = true
            }
        )

        SectionHeader("Appearance")
        DropdownSetting(
            title = stringResource(R.string.settings_theme),
            current = settings.themeMode,
            options = ThemeMode.entries,
            label = { themeLabel(it) },
            onSelect = { viewModel.setTheme(it) }
        )

        SectionHeader("Storage")
        OutlinedButton(
            onClick = {
                viewModel.clearCache {
                    Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) { Text(stringResource(R.string.settings_clear_cache)) }

        SectionHeader(stringResource(R.string.settings_legal))
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.disclaimer_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp)
            )
        }

        ClickableSetting(
            title = stringResource(R.string.about_developer),
            value = viewModel.developerName,
            onClick = onOpenAbout
        )

        Text(
            text = stringResource(R.string.developer_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp)
        )
    }

    if (showSaveLocationSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSaveLocationSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                )
            }
        ) {
            SaveLocationSheetContent(
                settings = settings,
                customFolderStatus = customFolderStatus,
                onSaveSmartFolders = { rawSubfolder ->
                    val sanitized = AppSettings.sanitizeSubfolder(rawSubfolder)
                    viewModel.useSmartMediaFolders(sanitized) {
                        showSaveLocationSheet = false
                        Toast.makeText(
                            context,
                            "Save location set to Smart Media Folders ($sanitized)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onLaunchFolderPicker = {
                    val initialUri = settings.customSaveTreeUri
                        ?.takeIf { it.isNotBlank() }
                        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    runCatching {
                        folderPickerLauncher.launch(initialUri)
                    }.onFailure {
                        Toast.makeText(
                            context,
                            "No folder picker available on this device",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onToggleOrganizeByType = { enabled ->
                    viewModel.setCustomSaveOrganizeByType(enabled)
                },
                onDismiss = { showSaveLocationSheet = false }
            )
        }
    }
}

@Composable
private fun SaveLocationSheetContent(
    settings: AppSettings,
    customFolderStatus: CustomFolderStatus,
    onSaveSmartFolders: (String) -> Unit,
    onLaunchFolderPicker: () -> Unit,
    onToggleOrganizeByType: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCustomMode by remember(settings.isCustomSaveLocation) {
        mutableStateOf(settings.isCustomSaveLocation)
    }
    var subfolderInput by remember(settings.saveSubfolder) {
        mutableStateOf(settings.saveSubfolder)
    }
    val previewSubfolder = AppSettings.sanitizeSubfolder(subfolderInput)
    val customTreeUri = settings.customSaveTreeUri?.takeIf { it.isNotBlank() }
    val customReadablePath = customTreeUri?.let { formatTreeUriForDisplay(it) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.settings_save_location),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Choose automatic MediaStore folders or browse your device for a custom folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Mode A: Smart Media Folders (Default)
        SaveModeCard(
            title = "Smart Media Folders",
            badge = "Default",
            description = "Automatically sorts files into Movies, Music, and Pictures so downloads appear natively in Gallery & Music apps.",
            icon = Icons.Rounded.PermMedia,
            selected = !selectedCustomMode,
            onClick = { selectedCustomMode = false }
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = subfolderInput,
                onValueChange = { subfolderInput = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Subfolder name") },
                placeholder = { Text(AppSettings.DEFAULT_SUBFOLDER) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Videos: Movies/$previewSubfolder/\n" +
                        "Audio: Music/$previewSubfolder/\n" +
                        "Images: Pictures/$previewSubfolder/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            if (previewSubfolder != AppSettings.DEFAULT_SUBFOLDER) {
                TextButton(
                    onClick = { subfolderInput = AppSettings.DEFAULT_SUBFOLDER },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("Reset to ${AppSettings.DEFAULT_SUBFOLDER}")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onSaveSmartFolders(subfolderInput) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Indigo,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (settings.isCustomSaveLocation) "Switch to Smart Folders" else "Save",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }

        // Mode B: Custom Folder (Device Browser via SAF OpenDocumentTree)
        SaveModeCard(
            title = "Custom Folder",
            badge = null,
            description = "Browse internal storage or SD card and save downloads to any folder you choose.",
            icon = Icons.Rounded.FolderOpen,
            selected = selectedCustomMode,
            onClick = { selectedCustomMode = true }
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            if (customReadablePath != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Selected folder",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = customReadablePath,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        when (customFolderStatus) {
                            CustomFolderStatus.ACTIVE -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = Teal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Folder access active",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Teal,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            CustomFolderStatus.UNAVAILABLE -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Folder deleted or permission revoked — please re-select a folder.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            CustomFolderStatus.NONE -> Unit
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Button(
                onClick = onLaunchFolderPicker,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Indigo,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.CreateNewFolder,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (customReadablePath != null && customFolderStatus == CustomFolderStatus.ACTIVE) {
                        "Change folder"
                    } else {
                        "Choose folder"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleOrganizeByType(!settings.customSaveOrganizeByType) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Organize into Videos / Audio / Images subfolders",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (settings.customSaveOrganizeByType) {
                            "Creates Videos, Audio, and Images folders inside your chosen folder"
                        } else {
                            "Saves all downloads directly inside your chosen folder"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.customSaveOrganizeByType,
                    onCheckedChange = onToggleOrganizeByType,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Indigo
                    )
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.downloads_cancel))
            }
        }
    }
}

@Composable
private fun SaveModeCard(
    title: String,
    badge: String?,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    val borderColor = if (selected) Indigo else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 1.75.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) Indigo else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!badge.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Indigo.copy(alpha = 0.18f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) Indigo else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp)
                )
            }

            if (selected) {
                expandedContent()
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun <T> DropdownSetting(
    title: String,
    current: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    label(current),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Int,
    range: IntRange,
    valueLabel: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit
) {
    val clamped = value.coerceIn(range.first, range.last)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = valueLabel(clamped),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = clamped.toFloat(),
            onValueChange = { raw ->
                val snapped = raw.roundToInt().coerceIn(range.first, range.last)
                if (snapped != clamped) {
                    onChange(snapped)
                }
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = range.first.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = range.last.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ClickableSetting(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.DARK -> R.string.settings_theme_dark
        ThemeMode.LIGHT -> R.string.settings_theme_light
    }
)
