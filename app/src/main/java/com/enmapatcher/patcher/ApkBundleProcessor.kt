package com.enmapatcher.patcher

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class ApkBundleProcessor(private val context: Context) {


    suspend fun extractBundle(uri: Uri, workDir: File): Map<String, File> = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val result = mutableMapOf<String, File>()

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open input: $uri")

        try {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                var foundApk = false
                while (entry != null) {
                    if (entry.name.endsWith(".apk") && !entry.isDirectory) {
                        foundApk = true
                        val outFile = File(workDir, entry.name.substringAfterLast('/'))
                        outFile.outputStream().use { zis.copyTo(it) }
                        result[outFile.nameWithoutExtension] = outFile
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                if (!foundApk) {

                    result += extractPlainApk(uri, workDir)
                }
            }
        } catch (e: Exception) {
            result += extractPlainApk(uri, workDir)
        }

        result
    }

    private fun extractPlainApk(uri: Uri, workDir: File): Map<String, File> {
        val outFile = File(workDir, "base.apk")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            outFile.outputStream().use { input.copyTo(it) }
        }
        return mapOf("base" to outFile)
    }


    fun findAssetPack(apks: Map<String, File>): File? =
        apks.entries.firstOrNull { (name, _) ->
            name.contains("install_time", ignoreCase = true) ||
                    name.contains("asset_pack", ignoreCase = true) ||
                    name.contains("assets", ignoreCase = true)
        }?.value











    fun findInstalledApks(packageName: String): Pair<File, List<File>> {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        val base = File(appInfo.sourceDir)
        val splits = appInfo.splitSourceDirs
            ?.map { File(it) }
            ?.sortedWith(compareBy { f ->

                val n = f.nameWithoutExtension.lowercase()
                when {
                    "install_time" in n || "assetpack" in n ||
                            ("asset" in n && "pack" in n) -> 1
                    else -> 0
                }
            })
            ?: emptyList()
        return base to splits
    }


    fun findAssetPackFromInstalled(packageName: String): File? = try {
        findInstalledApks(packageName).first
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }


    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }
}
