package com.enmapatcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enmapatcher.MainViewModel
import com.enmapatcher.R
import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.EnmaCfg

private const val DISCORD_URL = "https://discord.gg/83Sn6hAyVP"
private const val PATCHER_RELEASES_URL = "https://github.com/hxgohxrr/Enma-Patcher/releases"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToPatch: () -> Unit,
    onNavigateToSaves: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val appInstalled by viewModel.appInstalled.collectAsState()
    val backupFile by viewModel.backupFile.collectAsState()
    val context = LocalContext.current
    val updateAvailable by viewModel.updateAvailable.collectAsState()

    val hasDex by viewModel.hasDexPatches.collectAsState()


    var showSmaliWarning by remember { mutableStateOf(false) }
    var showDrmWarning by remember { mutableStateOf(false) }
    var pendingPatch by remember { mutableStateOf(false) }
    var updateDismissed by remember { mutableStateOf(false) }


    if (pendingPatch && !showSmaliWarning && !showDrmWarning) {
        LaunchedEffect(Unit) {
            pendingPatch = false
            onNavigateToPatch()
        }
    }

    if (showSmaliWarning) {
        SecurityWarningDialog(
            title = stringResource(R.string.warning_smali_title),
            message = stringResource(R.string.warning_smali_message),
            holdButtonText = stringResource(R.string.warning_smali_hold),
            onConfirm = {
                showSmaliWarning = false

                val warnings = viewModel.getSecurityWarnings()
                if (warnings.showDrmWarning) showDrmWarning = true

            },
            onDismiss = { showSmaliWarning = false; pendingPatch = false },
        )
    }

    if (showDrmWarning) {
        SecurityWarningDialog(
            title = stringResource(R.string.warning_drm_title),
            message = stringResource(R.string.warning_drm_message),
            holdButtonText = stringResource(R.string.warning_drm_hold),
            onConfirm = { showDrmWarning = false },
            onDismiss = { showDrmWarning = false; pendingPatch = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(config?.appName ?: stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            AppInfoCard(config = config, settings = settings)

            if (updateAvailable != null && !updateDismissed) {
                UpdateBanner(
                    version = updateAvailable!!,
                    context = context,
                    onDismiss = { updateDismissed = true },
                )
            }

            TargetCard(
                settings = settings,
                viewModel = viewModel,
            )

            InstalledStatusCard(
                packageName = settings.effectivePackage(),
                installed = appInstalled,
            )

            Button(
                onClick = {
                    val warnings = viewModel.getSecurityWarnings()
                    pendingPatch = true
                    when {
                        warnings.showSmaliWarning -> showSmaliWarning = true
                        warnings.showDrmWarning -> showDrmWarning = true
                        else -> {  }
                    }
                },
                enabled = appInstalled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(stringResource(R.string.patch), style = MaterialTheme.typography.titleMedium)
            }

            if (backupFile != null) {
                OutlinedButton(
                    onClick = { installApk(context, backupFile!!.absolutePath) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text(stringResource(R.string.revert_backup), style = MaterialTheme.typography.bodyMedium)
                }
            }

            OutlinedButton(
                onClick = onNavigateToSaves,
                enabled = appInstalled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.saves_open), style = MaterialTheme.typography.bodyMedium)
            }

            if (!appInstalled) {
                Text(
                    text = stringResource(R.string.install_hint, config?.appName ?: settings.effectivePackage()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(1f))

            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_URL)))
                },
            ) {
                Text(
                    stringResource(R.string.join_discord),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


@Composable
private fun TargetCard(
    settings: AppSettings,
    viewModel: MainViewModel,
) {
    var showPicker by remember { mutableStateOf(false) }
    val manual = settings.targetMode == "manual"
    if (showPicker) {
        TargetPickerDialog(
            viewModel = viewModel,
            onDismiss = { showPicker = false },
            onPick = {
                viewModel.setManualPackage(it)
                showPicker = false
            },
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.target_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !manual,
                    onClick = { viewModel.setTargetMode("auto") },
                    label = { Text(stringResource(R.string.target_auto)) },
                )
                FilterChip(
                    selected = manual,
                    onClick = { viewModel.setTargetMode("manual") },
                    label = { Text(stringResource(R.string.target_manual)) },
                )
            }
            if (manual) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = settings.manualPackage.ifBlank { settings.effectivePackage() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { showPicker = true }) {
                        Text(stringResource(R.string.target_pick))
                    }
                }
            } else {
                Text(
                    text = settings.targetPackage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TargetPickerDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val apps by viewModel.installedApps.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.refreshInstalledApps() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.target_pick)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.target_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val filtered = apps.filter {
                    query.isBlank() ||
                        it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        TextButton(
                            onClick = { onPick(app.packageName) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun InstalledStatusCard(packageName: String, installed: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (installed)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (installed) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (installed)
                    MaterialTheme.colorScheme.onSecondaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer,
            )
            Column {
                Text(
                    text = stringResource(if (installed) R.string.app_detected else R.string.app_not_found),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (installed)
                        MaterialTheme.colorScheme.onSecondaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (installed)
                        MaterialTheme.colorScheme.onSecondaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun UpdateBanner(
    version: String,
    context: Context,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(R.string.update_available_title, version),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                text = stringResource(R.string.update_available_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PATCHER_RELEASES_URL)))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                    ),
                ) {
                    Text(stringResource(R.string.update_download))
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.update_dismiss),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppInfoCard(config: EnmaCfg?, settings: AppSettings) {
    val mods = settings.effectiveMods()
    val active = mods.count { it.enabled }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = config?.appName ?: stringResource(R.string.loading),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            if (mods.isEmpty()) {
                Text(
                    text = stringResource(R.string.main_no_mods),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = stringResource(R.string.main_mods_active, active),
                    style = MaterialTheme.typography.bodySmall,
                )
                for (mod in mods.take(4)) {
                    Text(
                        text = mod.displayName.ifBlank { stringResource(R.string.mod_unnamed) },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (mods.size > 4) {
                    Text(
                        text = stringResource(R.string.mod_files_more, mods.size - 4),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
