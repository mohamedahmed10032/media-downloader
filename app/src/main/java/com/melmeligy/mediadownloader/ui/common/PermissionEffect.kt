package com.melmeligy.mediadownloader.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.melmeligy.mediadownloader.R

/**
 * Requests the runtime permissions the app needs, with a clear rationale dialog first:
 * - POST_NOTIFICATIONS on Android 13+ (download progress notifications).
 * - WRITE_EXTERNAL_STORAGE on Android 8-9 (saving to shared storage; scoped storage on 10+
 *   needs no permission).
 */
@Composable
fun RequestRuntimePermissions() {
    val context = LocalContext.current
    var handled by rememberSaveable { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }
    val needed = remember { permissionsNeeded(context) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { handled = true }

    LaunchedEffect(Unit) {
        if (needed.isNotEmpty() && !handled) showRationale = true
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false; handled = true },
            title = { Text(stringResource(R.string.perm_combined_title)) },
            text = { Text(stringResource(R.string.perm_combined_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    launcher.launch(needed.toTypedArray())
                }) { Text(stringResource(R.string.perm_grant)) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false; handled = true }) {
                    Text(stringResource(R.string.perm_not_now))
                }
            }
        )
    }
}

private fun permissionsNeeded(context: Context): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !granted(context, Manifest.permission.POST_NOTIFICATIONS)
    ) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        !granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    ) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
