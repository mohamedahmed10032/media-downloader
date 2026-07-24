package com.melmeligy.mediadownloader.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.AudioBitrate
import com.melmeligy.mediadownloader.domain.model.ThemeMode
import com.melmeligy.mediadownloader.domain.model.VideoQuality
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var editingFolder by remember { mutableStateOf(false) }

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
            onChange = { viewModel.setRetryCount(it) }
        )
        ClickableSetting(
            title = stringResource(R.string.settings_save_location),
            value = "Movies|Music|Pictures / ${settings.saveSubfolder}",
            onClick = { editingFolder = true }
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

    if (editingFolder) {
        var folder by remember { mutableStateOf(settings.saveSubfolder) }
        AlertDialog(
            onDismissRequest = { editingFolder = false },
            title = { Text(stringResource(R.string.settings_save_location)) },
            text = {
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    singleLine = true,
                    label = { Text("Subfolder name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setSaveSubfolder(folder)
                    editingFolder = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingFolder = false }) { Text(stringResource(R.string.perm_not_now)) }
            }
        )
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
    label: (T) -> String,
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
private fun SliderSetting(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0)
        )
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
