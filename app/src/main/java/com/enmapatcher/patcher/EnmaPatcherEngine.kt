package com.enmapatcher.patcher

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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

        // Clean up previous cache dirs in background — doesn't block patching
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

        // Locate APK + fetch currentLabel in parallel with network download
        val locateDeferred = async(Dispatchers.IO) {
            val (baseApk, assetPackApk) = bundleProcessor.findInstalledApks(packageName)
            val label = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0)
                    .loadLabel(context.packageManager).toString()
                    .takeIf { it != packageName && it.isNotBlank() }
            }.getOrNull()
            Triple(baseApk, assetPackApk, label)
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
        var assetPackApk: File? = null
        var currentLabel: String? = null
        step(context.getString(R.string.step_locate_apk), packageName) {
            val (base, assetPack, label) = locateDeferred.await()
            targetApk = base
            assetPackApk = assetPack
            currentLabel = label
        }

        // Optional: backup original APK before patching
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
                currentLabel = currentLabel,
                mergeApk = assetPackApk,
            )
        }

        val keystoreDir = File(context.filesDir, "keystore")
        val signer = ApkSigner(keystoreDir)
        val outputDir = (context.getExternalFilesDir("patched") ?: File(context.cacheDir, "patched"))
            .also { it.mkdirs() }
        val signedApk = File(outputDir, "patched_signed.apk")
        step(
            context.getString(R.string.step_sign_apk),
            context.getString(R.string.step_sign_apk_desc),
        ) {
            signer.sign(patchedUnsigned!!, signedApk)
        }

        // Re-align STORED entries after signing — the signer inserts META-INF
        // entries that shift byte offsets, destroying resources.arsc alignment.
        step(
            context.getString(R.string.step_align_apk),
            context.getString(R.string.step_align_apk_desc),
        ) {
            apkPatcher.zipAlign(signedApk)
        }

        // V2 signing — required on Android 11+ for apps with targetSdk >= 30.
        // Must run AFTER zipalign: V2 covers byte positions, any ZIP rewrite
        // after this would invalidate the V2 block.
        step(
            context.getString(R.string.step_sign_v2),
            context.getString(R.string.step_sign_v2_desc),
        ) {
            val ksFile = File(keystoreDir, "enmapatcher.p12")
            val ks = java.security.KeyStore.getInstance("PKCS12")
            ksFile.inputStream().use { ks.load(it, "enmapatcher".toCharArray()) }
            val key = ks.getKey("enmapatcher", "enmapatcher".toCharArray()) as java.security.PrivateKey
            val cert = ks.getCertificate("enmapatcher") as java.security.cert.X509Certificate
            val signerCfg = com.android.apksig.ApkSigner.SignerConfig.Builder(
                "enmapatcher", key, listOf(cert)
            ).build()
            val tmp = File(outputDir, "patched_v2_tmp.apk")
            com.android.apksig.ApkSigner.Builder(listOf(signerCfg))
                .setInputApk(signedApk)
                .setOutputApk(tmp)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign()
            if (!tmp.renameTo(signedApk)) {
                tmp.copyTo(signedApk, overwrite = true)
                tmp.delete()
            }
        }

        // Work dir no longer needed
        cacheRoot.deleteRecursively()

        Result(signedApk, config, backupApk)
    }

    private fun backupOriginal(apk: File, settings: AppSettings): File {
        val dir = context.getExternalFilesDir("backups") ?: File(context.filesDir, "backups")
        dir.mkdirs()
        val dest = File(dir, "original_backup.apk")
        apk.copyTo(dest, overwrite = true)

        // Also copy to user-chosen SAF folder if configured
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
