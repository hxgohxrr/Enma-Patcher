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
        val trimmed = pattern.replace('\\', '/').trim()
        if (trimmed.isEmpty()) return false
        val normalized = trimmed.trimEnd('/')
        if (trimmed.endsWith("/")) {
            return path == normalized || path.startsWith(normalized + "/") ||
                path.contains("/$normalized/")
        }
        if ('*' !in normalized && '?' !in normalized) {
            return path == normalized || path.startsWith(normalized + "/")
        }
        val target = if ('/' in normalized) path else path.substringAfterLast('/')
        if (target == normalized) return true
        val regex = StringBuilder("^")
        var i = 0
        while (i < normalized.length) {
            when {
                normalized.startsWith("**/", i) -> {
                    regex.append("(.*/)?")
                    i += 3
                }
                normalized.startsWith("**", i) -> {
                    regex.append(".*")
                    i += 2
                }
                normalized[i] == '*' -> {
                    regex.append("[^/]*")
                    i++
                }
                normalized[i] == '?' -> {
                    regex.append("[^/]")
                    i++
                }
                else -> {
                    regex.append(Regex.escape(normalized[i].toString()))
                    i++
                }
            }
        }
        regex.append("$")
        return Regex(regex.toString()).matches(target)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun sanitize(raw: String) = raw.replace(Regex(",\\s*(?=[}\\]])"), "")

        fun fromJson(raw: String): EnmaCfg {
            val normalized = sanitize(raw)
                .replace(Regex("\"include_ios\"\\s*:"), "\"include_iOS\":")
                .replace(Regex("\"exclude_ios\"\\s*:"), "\"exclude_iOS\":")
            return json.decodeFromString(normalized)
        }
    }
}
