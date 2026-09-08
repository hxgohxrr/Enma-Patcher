package com.enmapatcher.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val targetPackage: String = "jp.co.level5.yws1",
    val githubRepo: String = "ChipLG08/YW1MESP",
    val githubBranch: String = "main",
    val autoInstall: Boolean = false,
    val keepPatchedApk: Boolean = true,
    val language: String = "",
    val backupEnabled: Boolean = false,
    val backupFolderUri: String = "",
    val drmbUri: String = "",
    val localPatchZipUri: String = "",
    val mods: List<ModEntry> = emptyList(),
    val policyUrl: String = ModPolicy.DEFAULT_URL,
    val targetMode: String = "auto",
    val manualPackage: String = "",
    val appNameOverride: String = ""
) {
    val githubOwner: String get() = githubRepo.substringBefore("/")
    val githubRepoName: String get() = githubRepo.substringAfter("/")

    fun effectivePackage(): String =
        if (targetMode == "manual" && manualPackage.isNotBlank()) manualPackage.trim()
        else targetPackage

    fun effectiveAppName(configured: String?): String? {
        if (appNameOverride.isNotBlank()) return appNameOverride
        return configured?.takeIf { it.isNotBlank() }
    }

    fun effectiveMods(): List<ModEntry> {
        if (mods.isNotEmpty()) return mods
        val migrated = mutableListOf<ModEntry>()
        if (githubRepo.isNotBlank() && "/" in githubRepo) {
            migrated += ModEntry(
                kind = ModKind.GITHUB,
                repo = githubRepo.trim(),
                branch = githubBranch.trim().ifBlank { "main" },
                enabled = true
            )
        }
        if (localPatchZipUri.isNotBlank()) {
            migrated += ModEntry(
                kind = ModKind.ZIP,
                zipUri = localPatchZipUri,
                zipName = localPatchZipUri.substringAfterLast("/"),
                enabled = true
            )
        }
        return migrated
    }

    fun withMigratedMods(): AppSettings {
        if (mods.isNotEmpty()) return this
        val migrated = effectiveMods()
        if (migrated.isEmpty()) return this
        return copy(mods = migrated)
    }
}
