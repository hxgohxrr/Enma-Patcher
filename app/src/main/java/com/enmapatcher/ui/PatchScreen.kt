package com.enmapatcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import com.enmapatcher.MainViewModel
import com.enmapatcher.R
import com.enmapatcher.model.PatchState
import com.enmapatcher.model.PatchStep
import com.enmapatcher.model.PatchStepStatus
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.patchState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.patching)) })
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is PatchState.Patching -> PatchingProgress(steps = s.steps)
                is PatchState.Success -> SuccessPanel(
                    outputPath = s.outputPath,
                    targetPackage = settings.effectivePackage(),
                    context = context,
                    onBack = { viewModel.resetState(); onBack() },
                )
                is PatchState.Error -> ErrorPanel(
                    message = s.message,
                    onRetry = { viewModel.resetState(); onBack() },
                )
                else -> CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun PatchingProgress(steps: List<PatchStep>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.applying_patches),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(steps) { step -> StepRow(step) }
        }
    }
}

@Composable
private fun StepRow(step: PatchStep) {
    val (icon, tint) = when (step.status) {
        PatchStepStatus.DONE -> Icons.Default.Check to Color(0xFF4CAF50)
        PatchStepStatus.ERROR -> Icons.Default.Close to Color(0xFFF44336)
        PatchStepStatus.RUNNING -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.primary
        PatchStepStatus.PENDING -> Icons.Default.HourglassEmpty to Color.Gray
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (step.status == PatchStepStatus.RUNNING) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column {
            Text(step.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (step.description.isNotBlank()) {
                Text(
                    step.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SuccessPanel(
    outputPath: String,
    targetPackage: String,
    context: Context,
    onBack: () -> Unit,
) {
    var pendingInstall by rememberSaveable { mutableStateOf(false) }
    var pendingInstallAfterPerm by rememberSaveable { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }


    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (pendingInstallAfterPerm) {
                    val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                        context.packageManager.canRequestPackageInstalls()
                    if (canInstall) {
                        pendingInstallAfterPerm = false
                        val installed = runCatching {
                            context.packageManager.getApplicationInfo(targetPackage, 0); true
                        }.getOrDefault(false)
                        if (installed) showUninstallDialog = true
                        else runCatching { installApk(context, outputPath) }
                    }
                }
                if (pendingInstall) {
                    val stillInstalled = runCatching {
                        context.packageManager.getApplicationInfo(targetPackage, 0); true
                    }.getOrDefault(false)
                    if (!stillInstalled) {
                        pendingInstall = false
                        val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                            context.packageManager.canRequestPackageInstalls()
                        if (canInstall) {
                            runCatching { installApk(context, outputPath) }
                        } else {
                            pendingInstallAfterPerm = true
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            title = { Text(stringResource(R.string.uninstall_first_title)) },
            text = { Text(stringResource(R.string.uninstall_first_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showUninstallDialog = false
                    pendingInstall = true
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$targetPackage"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text(stringResource(R.string.open_app_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(64.dp),
        )
        Text(
            stringResource(R.string.patch_success),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Button(onClick = {
                val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    context.packageManager.canRequestPackageInstalls()
                if (!canInstall) {
                    pendingInstallAfterPerm = true
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                } else {
                    val installed = runCatching {
                        context.packageManager.getApplicationInfo(targetPackage, 0); true
                    }.getOrDefault(false)
                    if (installed) {
                        showUninstallDialog = true
                    } else {
                        runCatching { installApk(context, outputPath) }

                    }
                }
            }) {
                Text(stringResource(R.string.install_apk))
            }
        }
        TextButton(onClick = { shareApk(context, outputPath) }) {
            Text(stringResource(R.string.share_apk))
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFF44336), modifier = Modifier.size(64.dp))
        Text(stringResource(R.string.patch_error), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

private fun shareApk(context: Context, path: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", File(path))
    context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        context.getString(R.string.share_apk_title),
    ))
}
