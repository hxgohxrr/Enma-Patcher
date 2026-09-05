package com.enmapatcher.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ModKind {
    GITHUB,
    ZIP
}

@Serializable
data class ModEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: ModKind = ModKind.GITHUB,
    val repo: String = "",
    val branch: String = "main",
    val zipUri: String = "",
    val zipName: String = "",
    val enabled: Boolean = true
) {
    val owner: String get() = repo.substringBefore("/", "")
    val repoName: String get() = if ("/" in repo) repo.substringAfter("/") else ""
    val displayName: String get() = if (kind == ModKind.GITHUB) {
        if (repo.isBlank()) "" else "$repo@${branch.ifBlank { "main" }}"
    } else {
        zipName.ifBlank { zipUri.substringAfterLast("/") }
    }
}
