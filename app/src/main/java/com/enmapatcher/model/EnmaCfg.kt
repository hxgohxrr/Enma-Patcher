package com.enmapatcher.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EnmaCfg(
    val appName: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun sanitize(raw: String) = raw.replace(Regex(",\\s*(?=[}\\]])"), "")

        fun fromJson(raw: String): EnmaCfg = json.decodeFromString(sanitize(raw))
    }
}
