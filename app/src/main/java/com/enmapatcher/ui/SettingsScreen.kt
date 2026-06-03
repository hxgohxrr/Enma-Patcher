package com.enmapatcher.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.enmapatcher.MainViewModel
import com.enmapatcher.R
import com.enmapatcher.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    var drmbUri by remember(settings.drmbUri) { mutableStateOf(settings.drmbUri) }
    var drmbCopying by remember { mutableStateOf(false) }
    var drmbError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()



    val drmbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            drmbError = null
            drmbCopying = true
            coroutineScope.launch(Dispatchers.IO) {
                val dest = File(context.filesDir, "bypass.zip")
                dest.delete()
                val result = runCatching {

                    val inp: java.io.InputStream =
                        runCatching { context.contentResolver.openInputStream(selectedUri) }.getOrNull()

                        ?: runCatching {
                            context.contentResolver.openFileDescriptor(selectedUri, "r")
                                ?.let { java.io.FileInputStream(it.fileDescriptor) }
                        }.getOrNull()


                        ?: runCatching {
                            val id = ContentUris.parseId(selectedUri)
                            listOf("external_primary", "external").firstNotNullOfOrNull { vol ->
                                runCatching {
                                    context.contentResolver.query(
                                        MediaStore.Files.getContentUri(vol),
                                        arrayOf(MediaStore.Files.FileColumns.DATA),
                                        "${MediaStore.Files.FileColumns._ID} = ?",
                                        arrayOf(id.toString()),
                                        null,
                                    )?.use { c ->
                                        if (c.moveToFirst()) c.getString(0) else null
                                    }?.let { path -> File(path).takeIf { it.exists() }?.inputStream() }
                                }.getOrNull()
                            }
                        }.getOrNull()

                        ?: runCatching {
                            context.contentResolver.query(
                                selectedUri,
                                arrayOf(MediaStore.MediaColumns.DATA),
                                null, null, null,
                            )?.use { c ->
                                if (c.moveToFirst()) c.getString(0) else null
                            }?.let { File(it).inputStream() }
                        }.getOrNull()
                        ?: error("No se pudo abrir el archivo. URI: $selectedUri")
                    inp.use { it.copyTo(dest.outputStream()) }
                }
                withContext(Dispatchers.Main) {
                    drmbCopying = false
                    val success = result.isSuccess && dest.exists() && dest.length() > 0
                    val newPath = if (success) dest.absolutePath else ""
                    drmbUri = newPath
                    drmbError = if (success) null
                        else result.exceptionOrNull()?.message
                            ?: "Error: archivo vacío o no encontrado (${dest.length()} bytes)"

                    if (success) viewModel.updateDrmbUri(newPath)
                }
            }
        }
    }

    var waitingForStoragePerm by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && waitingForStoragePerm) {
                waitingForStoragePerm = false
                val hasStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                    Environment.isExternalStorageManager()
                if (hasStorage) drmbPicker.launch(arrayOf("*/*"))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openDrmbPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            waitingForStoragePerm = true
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } else {
            drmbPicker.launch(arrayOf("*/*"))
        }
    }

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
                .verticalScroll(rememberScrollState())
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.drmb_bypass), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.drmb_bypass_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (drmbCopying) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "Copiando archivo…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (drmbUri.isBlank()) {
                            stringResource(R.string.drmb_none)
                        } else {
                            File(drmbUri).name.ifBlank { drmbUri.substringAfterLast('/') }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { openDrmbPicker() }, enabled = !drmbCopying) {
                        Text(stringResource(R.string.drmb_select))
                    }
                    if (drmbUri.isNotBlank()) {
                        TextButton(onClick = { drmbUri = ""; drmbError = null; viewModel.updateDrmbUri("") }) {
                            Text(stringResource(R.string.reset))
                        }
                    }
                }
                if (drmbError != null) {
                    Text(
                        text = "⚠ $drmbError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                enabled = !drmbCopying,
                onClick = {
                    viewModel.updateSettings(
                        AppSettings(
                            githubRepo = repo.trim().ifBlank { "hxgohxrr/YW1MESP" },
                            githubBranch = branch.trim().ifBlank { "main" },
                            autoInstall = autoInstall,
                            language = language,
                            backupEnabled = backupEnabled,
                            backupFolderUri = backupFolderUri,
                            drmbUri = drmbUri,
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
