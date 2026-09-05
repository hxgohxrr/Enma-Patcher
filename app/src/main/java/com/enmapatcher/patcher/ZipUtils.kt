package com.enmapatcher.patcher

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object ZipUtils {

    data class ZipPreview(val name: String, val files: List<String>, val totalCount: Int, val truncated: Boolean)

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(idx) ?: uri.lastPathSegment.orEmpty()
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast("/") ?: uri.toString().substringAfterLast("/")
    }

    fun previewLocalZip(context: Context, uriString: String, limit: Int = 200): ZipPreview {
        val uri = Uri.parse(uriString)
        val name = displayName(context, uri)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("ZipOpenFailed")
        val files = stream.use { GithubPatchSource.listLocalZipEntries(it, limit + 1) }
        val truncated = files.size > limit
        val shown = if (truncated) files.take(limit) else files
        return ZipPreview(name, shown, files.size, truncated)
    }

    fun previewRemoteFiles(owner: String, repo: String, branch: String, limit: Int = 200): ZipPreview {
        val files = GithubPatchSource.listRepoFiles(owner, repo, branch).sorted()
        val truncated = files.size > limit
        val shown = if (truncated) files.take(limit) else files
        return ZipPreview("$repo@$branch", shown, files.size, truncated)
    }
}
