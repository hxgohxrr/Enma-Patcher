package com.enmapatcher.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

fun installApk(context: Context, path: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", File(path))
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
