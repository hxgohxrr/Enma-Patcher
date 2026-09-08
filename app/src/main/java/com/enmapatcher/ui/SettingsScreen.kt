package com.enmapatcher.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.enmapatcher.BuildConfig
import com.enmapatcher.MainViewModel
import com.enmapatcher.R
import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.ModEntry
import com.enmapatcher.model.ModKind
import com.enmapatcher.model.peerConflict
import com.enmapatcher.model.supportsAndroid
import com.enmapatcher.model.versionBlocked
import com.enmapatcher.patcher.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val mods = settings.effectiveMods()
    val modConfigs by viewModel.modConfigs.collectAsState()
    val gameVersion by viewModel.gameVersion.collectAsState()
    val config by viewModel.config.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshModConfigs() }
    var autoInstall by remember(settings.autoInstall) { mutableStateOf(settings.autoInstall) }
    var language by remember(settings.language) { mutableStateOf(settings.language) }
    var backupEnabled by remember(settings.backupEnabled) { mutableStateOf(settings.backupEnabled) }
    var backupFolderUri by remember(settings.backupFolderUri) { mutableStateOf(settings.backupFolderUri) }
    var drmbUri by remember(settings.drmbUri) { mutableStateOf(settings.drmbUri) }
    var drmbCopying by remember { mutableStateOf(false) }
    var drmbError by remember { mutableStateOf<String?>(null) }
    var policyUrl by remember(settings.policyUrl) { mutableStateOf(settings.policyUrl) }
    var appNameOverride by remember(settings.appNameOverride) { mutableStateOf(settings.appNameOverride) }
    var showAddRepo by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val drmbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            drmbError = null
            drmbCopying = true
            val openFailed = context.getString(R.string.drmb_open_failed, selectedUri.toString())
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
                        ?: error(openFailed)
                    inp.use { it.copyTo(dest.outputStream()) }
                }
                withContext(Dispatchers.Main) {
                    drmbCopying = false
                    val success = result.isSuccess && dest.exists() && dest.length() > 0
                    val newPath = if (success) dest.absolutePath else ""
                    drmbUri = newPath
                    drmbError = if (success) null
                    else result.exceptionOrNull()?.message
                        ?: context.getString(R.string.drmb_empty_error, dest.length())
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
    val modZipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            var name = it.lastPathSegment?.substringAfterLast("/").orEmpty()
            runCatching {
                context.contentResolver.query(it, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx).orEmpty()
                }
            }
            viewModel.addZipMod(it.toString(), name)
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
    val systemString = stringResource(R.string.language_system)
    val languageOptions = remember(systemString) {
        val options = mutableListOf("" to systemString)
        BuildConfig.SUPPORTED_LOCALES.forEach { code ->
            val locale = if (code.contains("-r")) {
                val parts = code.split("-r")
                Locale(parts[0], parts[1])
            } else {
                Locale(code)
            }
            val conf = android.content.res.Configuration(context.resources.configuration)
            conf.setLocale(locale)
            val localizedContext = context.createConfigurationContext(conf)
            val displayName = localizedContext.getString(R.string.language_local)
            options.add(code to displayName)
        }
        options
    }
    if (showAddRepo) {
        AddRepoDialog(
            onDismiss = { showAddRepo = false },
            onAdd = { repo, branch ->
                viewModel.addGithubMod(repo, branch)
                showAddRepo = false
            },
        )
    }
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
            Text(stringResource(R.string.mods_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.mods_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.mods_priority_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (mods.isEmpty()) {
                Text(
                    stringResource(R.string.mods_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for ((index, mod) in mods.withIndex()) {
                    ModCard(
                        mod = mod,
                        isFirst = index == 0,
                        isLast = index == mods.size - 1,
                        allMods = mods,
                        configs = modConfigs,
                        gameVersion = gameVersion,
                        onMoveUp = { viewModel.moveMod(mod.id, -1) },
                        onMoveDown = { viewModel.moveMod(mod.id, 1) },
                        onDelete = { viewModel.removeMod(mod.id) },
                        onToggle = { viewModel.toggleMod(mod.id, it) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showAddRepo = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.mod_add_repo))
                }
                OutlinedButton(
                    onClick = { modZipPicker.launch(arrayOf("application/zip", "*/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.mod_add_zip))
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.appname_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.appname_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = appNameOverride,
                onValueChange = { appNameOverride = it },
                label = { Text(stringResource(R.string.appname_title)) },
                placeholder = { Text(config?.appName ?: stringResource(R.string.appname_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()
            Text(stringResource(R.string.policy_url_label), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.policy_url_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = policyUrl,
                onValueChange = { policyUrl = it },
                label = { Text(stringResource(R.string.policy_url_label)) },
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
                        stringResource(R.string.drmb_copying),
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
                        overflow = TextOverflow.Ellipsis,
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
                        text = stringResource(R.string.drmb_error_prefix, drmbError ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((code, label) in languageOptions) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == code, onClick = { language = code })
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !drmbCopying,
                onClick = {
                    val currentMods = viewModel.settings.value.effectiveMods()
                    val firstGithub = currentMods.firstOrNull { it.kind == ModKind.GITHUB && it.repo.isNotBlank() }
                    viewModel.updateSettings(
                        AppSettings(
                            targetPackage = viewModel.settings.value.targetPackage,
                            githubRepo = firstGithub?.repo ?: viewModel.settings.value.githubRepo,
                            githubBranch = firstGithub?.branch ?: viewModel.settings.value.githubBranch,
                            autoInstall = autoInstall,
                            keepPatchedApk = viewModel.settings.value.keepPatchedApk,
                            language = language,
                            backupEnabled = backupEnabled,
                            backupFolderUri = backupFolderUri,
                            drmbUri = drmbUri,
                            localPatchZipUri = viewModel.settings.value.localPatchZipUri,
                            mods = currentMods,
                            policyUrl = policyUrl.trim(),
                            appNameOverride = appNameOverride.trim(),
                            targetMode = viewModel.settings.value.targetMode,
                            manualPackage = viewModel.settings.value.manualPackage,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModCard(
    mod: ModEntry,
    isFirst: Boolean,
    isLast: Boolean,
    allMods: List<ModEntry>,
    configs: Map<String, com.enmapatcher.model.EnmaCfg>,
    gameVersion: String?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (mod.kind == ModKind.GITHUB) stringResource(R.string.mod_github_badge)
                            else stringResource(R.string.mod_zip_badge)
                        )
                    },
                )
                Text(
                    text = mod.displayName.ifBlank { stringResource(R.string.mod_unnamed) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Switch(checked = mod.enabled, onCheckedChange = onToggle)
            }
            val cfg = configs[mod.id]
            val androidOk = mod.supportsAndroid(configs)
            val iosOk = cfg?.effectiveIos() == true
            val conflict = mod.peerConflict(allMods, configs)
            val blockedVer = mod.versionBlocked(gameVersion, configs)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (androidOk) {
                    AssistChip(onClick = {}, label = { Text("Android") })
                }
                if (iosOk) {
                    AssistChip(onClick = {}, label = { Text("iOS") })
                }
                val license = cfg?.license.orEmpty()
                if (license.isNotBlank()) {
                    AssistChip(onClick = {}, label = { Text(license) })
                }
                if (cfg?.aiContent == true) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.ai_badge)) })
                }
                val rec = cfg?.recommendedVersion?.takeIf { it.isNotBlank() }
                if (rec != null) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.mod_rec_version, rec)) })
                }
                if (conflict == null && blockedVer == null && androidOk) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.mod_status_ok)) })
                }
            }
            if (!androidOk) {
                Text(
                    text = stringResource(R.string.mod_issue_platform),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (conflict != null) {
                Text(
                    text = stringResource(R.string.mod_issue_conflict, conflict),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (blockedVer != null) {
                Text(
                    text = stringResource(R.string.mod_issue_version, blockedVer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!mod.enabled) {
                Text(
                    text = stringResource(R.string.mod_disabled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMoveUp, enabled = !isFirst) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.mod_move_up))
                }
                IconButton(onClick = onMoveDown, enabled = !isLast) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.mod_move_down))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (expanded) stringResource(R.string.mod_hide_files)
                        else stringResource(R.string.mod_show_files)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.mod_delete))
                }
            }
            if (expanded) {
                ModFilesPreview(mod = mod)
            }
        }
    }
}

@Composable
private fun ModFilesPreview(mod: ModEntry) {
    val context = LocalContext.current
    var loading by remember(mod.id) { mutableStateOf(true) }
    var files by remember(mod.id) { mutableStateOf<List<String>>(emptyList()) }
    var total by remember(mod.id) { mutableStateOf(0) }
    var truncated by remember(mod.id) { mutableStateOf(false) }
    var failed by remember(mod.id) { mutableStateOf(false) }
    LaunchedEffect(mod.id, mod.zipUri, mod.repo, mod.branch) {
        loading = true
        failed = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (mod.kind == ModKind.GITHUB) {
                    ZipUtils.previewRemoteFiles(mod.owner, mod.repoName, mod.branch.ifBlank { "main" }, 200)
                } else {
                    ZipUtils.previewLocalZip(context, mod.zipUri, 200)
                }
            }.getOrNull()
        }
        if (result == null) {
            failed = true
            loading = false
        } else {
            files = result.files.take(30)
            total = result.totalCount
            truncated = result.truncated || result.totalCount > files.size
            loading = false
        }
    }
    when {
        loading -> Text(
            stringResource(R.string.mod_preview_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        failed -> Text(
            stringResource(R.string.mod_preview_error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (f in files) {
                Text(
                    text = f,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (truncated || total > files.size) {
                Text(
                    text = stringResource(R.string.mod_files_more, total - files.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun AddRepoDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mod_add_repo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text(stringResource(R.string.mod_repo_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    label = { Text(stringResource(R.string.mod_branch_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(repo.trim(), branch.trim().ifBlank { "main" }) },
                enabled = repo.trim().isNotBlank() && "/" in repo.trim(),
            ) {
                Text(stringResource(R.string.mod_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
