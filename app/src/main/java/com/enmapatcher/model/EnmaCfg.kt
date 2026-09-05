package com.enmapatcher.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

@Serializable
data class ModPlatforms(
    @SerialName("Android") val android: Boolean = false,
    @SerialName("iOS") val ios: Boolean = false,
)

@Serializable
data class EnmaCfg(
    val appName: String? = null,
    /** Override for the original app label if auto-detection fails (e.g. locale mismatch) */
    val currentLabel: String? = null,
    val version: String? = null,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    @SerialName("include_Android") val includeAndroid: List<String> = emptyList(),
    @SerialName("exclude_Android") val excludeAndroid: List<String> = emptyList(),
    @SerialName("include_iOS") val includeIos: List<String> = emptyList(),
    @SerialName("exclude_iOS") val excludeIos: List<String> = emptyList(),
    val platforms: ModPlatforms? = null,
    @SerialName("compatible_mods") val compatibleMods: List<String> = emptyList(),
    @SerialName("uncompatible_mods") val uncompatibleMods: List<String> = emptyList(),
    @SerialName("recommended_version") val recommendedVersion: String? = null,
    @SerialName("tested_versions") val testedVersions: List<String> = emptyList(),
    @SerialName("uncompatible_versions") val uncompatibleVersions: List<String> = emptyList(),
    @SerialName("ai_content") val aiContent: Boolean = false,
    @SerialName("License") val license: String? = null,
) {
    fun effectiveAndroid(): Boolean = platforms?.android ?: true

    fun effectiveIos(): Boolean = platforms?.ios ?: true

    fun allows(path: String, android: Boolean = true): Boolean {
        val extraExclude = if (android) excludeAndroid else excludeIos
        if ((exclude + extraExclude).any { matches(it, path) }) return false
        val extraInclude = if (android) includeAndroid else includeIos
        val wanted = include + extraInclude
        if (wanted.isEmpty()) return true
        return wanted.any { matches(it, path) }
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
