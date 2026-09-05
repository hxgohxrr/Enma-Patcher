package com.enmapatcher.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EnmaCfg(
    val appName: String? = null,
    /** Override for the original app label if auto-detection fails (e.g. locale mismatch) */
    val currentLabel: String? = null,
    val version: String? = null,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
) {
    fun allows(path: String): Boolean {
        if (exclude.any { matches(it, path) }) return false
        if (include.isEmpty()) return true
        return include.any { matches(it, path) }
    }

    private fun matches(pattern: String, path: String): Boolean {
        val normalized = pattern.replace('\\', '/').trim().trimEnd('/')
        if (normalized.isEmpty()) return false
        return path == normalized || path.startsWith(normalized + "/")
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun sanitize(raw: String) = raw.replace(Regex(",\\s*(?=[}\\]])"), "")

        fun fromJson(raw: String): EnmaCfg = json.decodeFromString(sanitize(raw))
    }
}
