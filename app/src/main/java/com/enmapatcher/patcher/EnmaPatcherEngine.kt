package com.enmapatcher.patcher

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.enmapatcher.R
import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.EnmaCfg
import com.enmapatcher.model.ModEntry
import com.enmapatcher.model.ModKind
import com.enmapatcher.model.ModPolicy
import com.enmapatcher.model.PatchStep
import com.enmapatcher.model.PatchStepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

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
        val mods = settings.effectiveMods().filter { it.enabled }
        if (mods.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.error_no_mods))
        }
        val policyChecker = ModPolicyChecker(settings.policyUrl)
        var policy = ModPolicy()
        step(
            context.getString(R.string.step_policy_check),
            context.getString(R.string.step_policy_check_desc)
        ) {
            policy = withContext(Dispatchers.IO) { policyChecker.loadPolicy() }
            for (mod in mods) {
                if (mod.kind == ModKind.GITHUB && mod.repo.isNotBlank()) {
                    policyChecker.checkRepo(policy, mod.repo)
                }
            }
        }
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
        var mergedConfig = EnmaCfg()
        val perModPatches = ArrayList<Pair<ModEntry, Map<String, ByteArray>>>(mods.size)
        val perModCounts = LinkedHashMap<String, Int>()
        for ((index, mod) in mods.withIndex()) {
            val modLabel = modLabel(mod)
            val title = context.getString(R.string.step_download_mod, index + 1, mods.size, modLabel)
            onStep(PatchStep(title, context.getString(R.string.step_download_mod_running), PatchStepStatus.RUNNING))
            try {
                val (cfg, files) = withContext(Dispatchers.IO) { downloadMod(mod) }
                policyChecker.checkPaths(policy, files.keys)
                policyChecker.checkContents(policy, files)
                perModPatches += mod to files
                perModCounts[mod.id] = files.size
                if (mergedConfig.appName.isNullOrBlank() && !cfg.appName.isNullOrBlank()) {
                    mergedConfig = mergedConfig.copy(appName = cfg.appName)
                }
                if (mergedConfig.currentLabel.isNullOrBlank() && !cfg.currentLabel.isNullOrBlank()) {
                    mergedConfig = mergedConfig.copy(currentLabel = cfg.currentLabel)
                }
                if (mergedConfig.version.isNullOrBlank() && !cfg.version.isNullOrBlank()) {
                    mergedConfig = mergedConfig.copy(version = cfg.version)
                }
                onStep(PatchStep(title, context.getString(R.string.step_download_mod_done, files.size), PatchStepStatus.DONE))
            } catch (e: SecurityException) {
                val msg = policyMessage(e)
                onStep(PatchStep(title, msg, PatchStepStatus.ERROR))
                throw SecurityException(msg, e)
            } catch (e: Exception) {
                onStep(PatchStep(title, "${e.javaClass.simpleName}: ${e.message}", PatchStepStatus.ERROR))
                throw e
            }
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
        var patchMap = LinkedHashMap<String, ByteArray>()
        for ((_, files) in perModPatches.asReversed()) {
            for ((path, bytes) in files) {
                patchMap[path] = bytes
            }
        }
        var bypassFile: File? = null
        var bypassSplitNames: Set<String> = emptySet()
        if (settings.drmbUri.isNotBlank()) {
            onStep(
                PatchStep(
                    context.getString(R.string.step_load_drmb),
                    context.getString(R.string.step_load_drmb_desc, 0, 0),
                    PatchStepStatus.RUNNING,
                )
            )
            runCatching {
                val uri = Uri.parse(settings.drmbUri)
                val (drmbBase, splitNames, drmbFile) = loadDrmb(uri)
                val merged = LinkedHashMap<String, ByteArray>(drmbBase.size + patchMap.size)
                merged.putAll(drmbBase)
                for ((path, bytes) in patchMap) {
                    merged[path] = bytes
                }
                patchMap = merged
                policyChecker.checkPaths(policy, drmbBase.keys)
                policyChecker.checkContents(policy, drmbBase)
                bypassFile = drmbFile
                bypassSplitNames = splitNames
                onStep(
                    PatchStep(
                        context.getString(R.string.step_load_drmb),
                        context.getString(R.string.step_load_drmb_desc, drmbBase.size, splitNames.size),
                        PatchStepStatus.DONE,
                    )
                )
            }.onFailure { e ->
                onStep(
                    PatchStep(
                        context.getString(R.string.step_load_drmb),
                        "${e.javaClass.simpleName}: ${e.message}",
                        PatchStepStatus.ERROR,
                    )
                )
            }
        }
        val smaliPatches = patchMap.filter { it.key.startsWith("smali/") }
        if (smaliPatches.isNotEmpty()) {
            val smaliTitle = context.getString(R.string.step_compile_smali)
            onStep(PatchStep(smaliTitle, context.getString(R.string.step_compile_smali_running, smaliPatches.size), PatchStepStatus.RUNNING))
            try {
                val dexPatches = withContext(Dispatchers.IO) {
                    SmaliDexPatcher().buildDexPatches(
                        targetApk!!,
                        smaliPatches,
                        File(cacheRoot, "smali_work"),
                    )
                }
                if (dexPatches.isEmpty()) {
                    throw IllegalStateException(context.getString(R.string.error_smali_failed, smaliPatches.size))
                }
                for (key in smaliPatches.keys) patchMap.remove(key)
                for ((path, bytes) in dexPatches) patchMap[path] = bytes
                onStep(PatchStep(smaliTitle, context.getString(R.string.step_compile_smali_done, dexPatches.size), PatchStepStatus.DONE))
            } catch (e: Exception) {
                onStep(PatchStep(smaliTitle, "${e.javaClass.simpleName}: ${e.message}", PatchStepStatus.ERROR))
                throw e
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
        val totalFiles = patchMap.size
        val orderDesc = mods.map { modLabel(it) }.joinToString(" > ")
        step(
            context.getString(R.string.step_apply_patches),
            context.getString(R.string.step_apply_patches_mods_desc, totalFiles, mods.size, orderDesc),
        ) {
            patchedUnsigned = apkPatcher.applyFileReplacements(
                targetApk!!,
                patchMap,
                appName = mergedConfig.appName?.takeIf { it.isNotBlank() },
                currentLabel = mergedConfig.currentLabel?.takeIf { it.isNotBlank() } ?: currentLabel,
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
        Result(signedApk, mergedConfig, backupApk)
    }

    private fun modLabel(mod: ModEntry): String {
        return if (mod.kind == ModKind.GITHUB) {
            if (mod.repo.isBlank()) context.getString(R.string.mod_unnamed) else mod.displayName
        } else {
            mod.zipName.ifBlank {
                mod.zipUri.substringAfterLast("/").ifBlank { context.getString(R.string.mod_unnamed) }
            }
        }
    }

    private fun policyMessage(e: SecurityException): String {
        val raw = e.message.orEmpty()
        val parts = raw.split(":")
        if (parts.size < 2) return context.getString(R.string.error_blocked_generic)
        return when (parts[0]) {
            "BlockedRepo" -> context.getString(R.string.error_blocked_repo, parts.getOrElse(1) { "" })
            "BlockedPath" -> context.getString(
                R.string.error_blocked_path,
                parts.getOrElse(1) { "" },
                parts.getOrElse(2) { "" }
            )
            else -> context.getString(
                R.string.error_blocked_word,
                parts.getOrElse(1) { "" },
                parts.getOrElse(2) { "" }
            )
        }
    }

    private fun downloadMod(mod: ModEntry): Pair<EnmaCfg, Map<String, ByteArray>> {
        return if (mod.kind == ModKind.GITHUB) {
            GithubPatchSource.fetchPatchesByRaw(mod.owner, mod.repoName, mod.branch.ifBlank { "main" }, null)
        } else {
            val uri = Uri.parse(mod.zipUri)
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException(context.getString(R.string.error_open_zip, modLabel(mod)))
            stream.use { GithubPatchSource.loadLocalZip(it) }
        }
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
            } catch (_: Exception) {
            }
        }
        return dest
    }
}
