package com.enmapatcher.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ModPolicy(
    val bannedRepos: List<String> = emptyList(),
    val bannedWords: List<String> = DEFAULT_BANNED_WORDS,
    val bannedPaths: List<String> = emptyList()
) {
    companion object {
        const val DEFAULT_URL = "https://raw.githubusercontent.com/hxgohxrr/Enma-Patcher/main/policy.json"
        val DEFAULT_BANNED_WORDS = listOf(
            "stealer",
            "malware",
            "trojan",
            "keylogger",
            "phishing",
            "scam",
            "spyware",
            "ransomware",
            "virus",
            "crack"
        )
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        fun fromJson(raw: String): ModPolicy = try {
            json.decodeFromString(sanitize(raw))
        } catch (_: Exception) {
            ModPolicy()
        }
        private fun sanitize(raw: String) = raw.replace(Regex(",\\s*(?=[}\\]])"), "")
    }

    fun findBlockedRepo(repo: String): String? {
        val norm = repo.trim().lowercase()
        return bannedRepos.firstOrNull { it.trim().lowercase() == norm }
    }

    fun findBlockedPath(path: String): String? {
        val norm = path.lowercase()
        return bannedPaths.firstOrNull { it.lowercase() in norm }
    }

    fun findBlockedWord(haystack: String): String? {
        val norm = haystack.lowercase()
        return bannedWords.firstOrNull { it.isNotBlank() && it.lowercase() in norm }
    }
}
