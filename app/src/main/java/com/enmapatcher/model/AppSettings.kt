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
) {
    val githubOwner: String get() = githubRepo.substringBefore("/")
    val githubRepoName: String get() = githubRepo.substringAfter("/")
}
