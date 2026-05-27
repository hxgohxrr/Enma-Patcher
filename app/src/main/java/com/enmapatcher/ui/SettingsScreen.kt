package com.enmapatcher.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enmapatcher.MainViewModel
import com.enmapatcher.R
import com.enmapatcher.model.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    var repo by remember(settings.githubRepo) { mutableStateOf(settings.githubRepo) }
    var branch by remember(settings.githubBranch) { mutableStateOf(settings.githubBranch) }
    var autoInstall by remember(settings.autoInstall) { mutableStateOf(settings.autoInstall) }
    var language by remember(settings.language) { mutableStateOf(settings.language) }
    var backupEnabled by remember(settings.backupEnabled) { mutableStateOf(settings.backupEnabled) }
    var backupFolderUri by remember(settings.backupFolderUri) { mutableStateOf(settings.backupFolderUri) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            backupFolderUri = it.toString()
        }
    }

    val languageOptions = listOf(
        "" to R.string.language_system,
        "es" to R.string.language_es,
        "en" to R.string.language_en,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.patches_repo), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                label = { Text(stringResource(R.string.github_repo_label)) },
                placeholder = { Text("hxgohxrr/YW1MESP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = branch,
                onValueChange = { branch = it },
                label = { Text(stringResource(R.string.branch_label)) },
                placeholder = { Text("main") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(stringResource(R.string.auto_install), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.auto_install_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoInstall, onCheckedChange = { autoInstall = it })
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(stringResource(R.string.backup_original), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.backup_original_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = backupEnabled, onCheckedChange = { backupEnabled = it })
            }

            if (backupEnabled) {
                Text(stringResource(R.string.backup_folder), style = MaterialTheme.typography.titleMedium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (backupFolderUri.isBlank()) {
                            stringResource(R.string.backup_default)
                        } else {
                            Uri.parse(backupFolderUri).lastPathSegment
                                ?.substringAfterLast(":")
                                ?: stringResource(R.string.backup_custom)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { folderPicker.launch(null) }) {
                        Text(stringResource(R.string.select))
                    }
                    if (backupFolderUri.isNotBlank()) {
                        TextButton(onClick = { backupFolderUri = "" }) {
                            Text(stringResource(R.string.reset))
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                languageOptions.forEachIndexed { index, (code, labelRes) ->
                    SegmentedButton(
                        selected = language == code,
                        onClick = { language = code },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = languageOptions.size),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.updateSettings(
                        AppSettings(
                            githubRepo = repo.trim().ifBlank { "hxgohxrr/YW1MESP" },
                            githubBranch = branch.trim().ifBlank { "main" },
                            autoInstall = autoInstall,
                            language = language,
                            backupEnabled = backupEnabled,
                            backupFolderUri = backupFolderUri,
                        )
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
