package com.enmapatcher.patcher

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream
import com.enmapatcher.R
import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.EnmaCfg
import com.enmapatcher.model.PatchStep
import com.enmapatcher.model.PatchStepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File

class EnmaPatcherEngine(private val context: Context) {

    data class Result(val outputApk: File, val config: EnmaCfg, val backupApk: File? = null)

    suspend fun patch(
        packageName: String,
        settings: AppSettings,
        onStep: (PatchStep) -> Unit,
    ): Result = coroutineScope {

        val cacheRoot = File(context.cacheDir, "enmapatcher_${System.currentTimeMillis()}")
        cacheRoot.mkdirs()


        async(Dispatchers.IO) {
            context.cacheDir.listFiles { f ->
                f.isDirectory && (
                    (f.name.startsWith("enmapatcher_") && f.name != cacheRoot.name) ||
                    f.name == "patched"
                )
            }?.forEach { it.deleteRecursively() }
        }

        suspend fun step(name: String, desc: String, block: suspend () -> Unit) {
            onStep(PatchStep(name, desc, PatchStepStatus.RUNNING))
            try {
                block()
                onStep(PatchStep(name, desc, PatchStepStatus.DONE))
            } catch (e: Exception) {
                onStep(PatchStep(name, desc, PatchStepStatus.ERROR))
                throw e
            }
        }

        val source = GithubPatchSource(settings)
        val bundleProcessor = ApkBundleProcessor(context)


        val locateDeferred = async(Dispatchers.IO) {
            val (baseApk, splits) = bundleProcessor.findInstalledApks(packageName)
            val labelFromPm = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0)
                    .loadLabel(context.packageManager).toString()
                    .takeIf { it != packageName && it.isNotBlank() }
            }.getOrNull()
            val label = labelFromPm ?: runCatching {
                val pm = context.packageManager
                pm.getPackageArchiveInfo(baseApk.absolutePath, 0)?.applicationInfo?.let { info ->
                    info.sourceDir = baseApk.absolutePath
                    info.publicSourceDir = baseApk.absolutePath
                    info.loadLabel(pm).toString().takeIf { it != packageName && it.isNotBlank() }
                }
            }.getOrNull()
            Triple(baseApk, splits, label)
        }

        var config = EnmaCfg()
        var patchMap: Map<String, ByteArray> = emptyMap()

        step(
            context.getString(R.string.step_download_patches),
            context.getString(R.string.step_download_patches_desc, settings.githubRepo),
        ) {
            val (cfg, files) = source.fetchConfigAndPatches()
            config = cfg
            patchMap = files
        }

        var targetApk: File? = null
        var splitApks: List<File> = emptyList()
        var currentLabel: String? = null
        step(context.getString(R.string.step_locate_apk), packageName) {
            val (base, splits, label) = locateDeferred.await()
            targetApk = base
            splitApks = splits
            currentLabel = label
        }



        var bypassFile: File? = null
        var bypassSplitNames: Set<String> = emptySet()
        if (settings.drmbUri.isNotBlank()) {
            onStep(PatchStep(
                context.getString(R.string.step_load_drmb),
                context.getString(R.string.step_load_drmb_desc, 0, 0),
                PatchStepStatus.RUNNING,
            ))
            runCatching {
                val uri = Uri.parse(settings.drmbUri)
                val (drmbBase, splitNames, drmbFile) = loadDrmb(uri)



                val smaliPatches = drmbBase.filter { it.key.startsWith("smali/") }
                val binaryPatches = drmbBase.filter { !it.key.startsWith("smali/") }

                val dexPatches = if (smaliPatches.isNotEmpty()) {
                    val patches = SmaliDexPatcher().buildDexPatches(
                        targetApk!!,
                        smaliPatches,
                        File(cacheRoot, "smali_work"),
                    )
                    if (patches.isEmpty()) error(
                        "Smali compilation failed — ${smaliPatches.size} .smali files, 0 DEX patches. " +
                        "Check .drmb smali files are valid for API 32."
                    )
                    patches
                } else emptyMap()


                patchMap = patchMap + binaryPatches + dexPatches

                bypassFile = drmbFile
                bypassSplitNames = splitNames
                onStep(PatchStep(
                    context.getString(R.string.step_load_drmb),
                    context.getString(R.string.step_load_drmb_desc, drmbBase.size, splitNames.size),
                    PatchStepStatus.DONE,
                ))
            }.onFailure { e ->
                onStep(PatchStep(
                    context.getString(R.string.step_load_drmb),
                    "${e.javaClass.simpleName}: ${e.message}",
                    PatchStepStatus.ERROR,
                ))
            }
        }


        var backupApk: File? = null
        if (settings.backupEnabled) {
            step(
                context.getString(R.string.step_backup_apk),
                context.getString(R.string.step_backup_apk_desc),
            ) {
                backupApk = backupOriginal(targetApk!!, settings)
            }
        }

        val apkPatcher = ApkPatcher(File(cacheRoot, "work"))
        var patchedUnsigned: File? = null
        step(
            context.getString(R.string.step_apply_patches),
            context.getString(R.string.step_apply_patches_desc, patchMap.size),
        ) {
            patchedUnsigned = apkPatcher.applyFileReplacements(
                targetApk!!,
                patchMap,
                appName = config.appName?.takeIf { it.isNotBlank() },
                currentLabel = config.currentLabel?.takeIf { it.isNotBlank() } ?: currentLabel,
                mergeApks = splitApks,
                bypassZip = bypassFile,
                bypassSplitNames = bypassSplitNames,
            )
        }

        val keystoreDir = File(context.filesDir, "keystore")
        val outputDir = (context.getExternalFilesDir("patched") ?: File(context.cacheDir, "patched"))
            .also { it.mkdirs() }
        val signedApk = File(outputDir, "patched_signed.apk")




        step(
            context.getString(R.string.step_sign_v2),
            context.getString(R.string.step_sign_v2_desc),
        ) {

            val (key, cert) = ApkSigner(keystoreDir).loadOrCreateKeyPair()
            val signerCfg = com.android.apksig.ApkSigner.SignerConfig.Builder(
                "enmapatcher", key, listOf(cert)
            ).build()
            com.android.apksig.ApkSigner.Builder(listOf(signerCfg))
                .setInputApk(patchedUnsigned!!)
                .setOutputApk(signedApk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign()
        }


        cacheRoot.deleteRecursively()

        Result(signedApk, config, backupApk)
    }





    private suspend fun loadDrmb(
        uri: Uri,
    ): Triple<Map<String, ByteArray>, Set<String>, File> = withContext(Dispatchers.IO) {
        val baseFiles = mutableMapOf<String, ByteArray>()
        val splitNames = mutableSetOf<String>()
        val file = File(uri.toString())
        ZipInputStream(file.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("base/") && !entry.isDirectory) {
                    baseFiles[name.removePrefix("base/")] = zis.readBytes()
                } else if (name.startsWith("split/") && !entry.isDirectory) {
                    splitNames += name.removePrefix("split/")
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Triple(baseFiles, splitNames, file)
    }

    private fun backupOriginal(apk: File, settings: AppSettings): File {
        val dir = context.getExternalFilesDir("backups") ?: File(context.filesDir, "backups")
        dir.mkdirs()
        val dest = File(dir, "original_backup.apk")
        apk.copyTo(dest, overwrite = true)


        if (settings.backupFolderUri.isNotBlank()) {
            try {
                val folder = DocumentFile.fromTreeUri(context, Uri.parse(settings.backupFolderUri))
                folder?.findFile("original_backup.apk")?.delete()
                val file = folder?.createFile("application/vnd.android.package-archive", "original_backup")
                file?.let {
                    context.contentResolver.openOutputStream(it.uri)?.use { out ->
                        dest.inputStream().use { inp -> inp.copyTo(out) }
                    }
                }
            } catch (_: Exception) {}
        }

        return dest
    }
}
