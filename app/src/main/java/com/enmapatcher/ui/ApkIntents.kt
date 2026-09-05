package com.enmapatcher.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun startUninstall(context: Context, packageName: String, onStarted: () -> Unit) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        onStarted()
    }.onFailure {

        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            onStarted()
        }
    }
}

fun installApk(context: Context, path: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", File(path))
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}






suspend fun installApkAsPlayStore(context: Context, path: String) {
    val apkFile = File(path)
    val pm = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setInstallerPackageName("com.android.vending")
        setSize(apkFile.length())
    }
    val sessionId = runCatching { pm.createSession(params) }.getOrElse {
        installApk(context, path); return
    }
    runCatching {
        withContext(Dispatchers.IO) {
            pm.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { it.copyTo(out, bufferSize = 65536) }
                    session.fsync(out)
                }
                withContext(Dispatchers.Main) {
                    val intent = Intent(context, Class.forName("${context.packageName}.MainActivity"))
                    val pi = PendingIntent.getActivity(
                        context, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pi.intentSender)
                }
            }
        }
    }.onFailure {
        runCatching { pm.abandonSession(sessionId) }
        installApk(context, path)
    }
}
