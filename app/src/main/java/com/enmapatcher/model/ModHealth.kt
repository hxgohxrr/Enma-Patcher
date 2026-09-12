package com.enmapatcher.model

fun ModEntry.supportsAndroid(configs: Map<String, EnmaCfg>): Boolean {
    return configs[id]?.effectiveAndroid() ?: true
}

fun ModEntry.peerConflict(allMods: List<ModEntry>, configs: Map<String, EnmaCfg>): String? {
    val mine = configs[id] ?: return null
    val mineBlocked = mine.uncompatibleMods.map { it.trim().lowercase() }
    if (mineBlocked.isEmpty()) return null
    for (peer in allMods) {
        if (peer.id == id || !peer.enabled) continue
        if (peer.kind == ModKind.GITHUB && peer.repo.trim().lowercase() in mineBlocked) {
            return peer.displayName.ifBlank { peer.repo }
        }
        val peerCfg = configs[peer.id]
        if (peerCfg != null && kind == ModKind.GITHUB &&
            repo.trim().lowercase() in peerCfg.uncompatibleMods.map { it.trim().lowercase() }
        ) {
            return peer.displayName.ifBlank { peer.repo }
        }
    }
    return null
}

fun ModEntry.versionBlocked(gameVersion: String?, configs: Map<String, EnmaCfg>): String? {
    if (gameVersion.isNullOrBlank()) return null
    val mine = configs[id] ?: return null
    return if (mine.uncompatibleVersions.any { it.trim() == gameVersion.trim() }) gameVersion else null
}

enum class ModOrigin {
    UNKNOWN,
    MOD_3DS,
    MOD_SWITCH,
}

data class ModFlags(
    val origin: ModOrigin = ModOrigin.UNKNOWN,
    val hasPatches: Boolean = false,
    val hasSubMods: Boolean = false,
    val ignored: Boolean = false,
)

fun detectOrigin(files: Collection<String>): ModOrigin {
    var threeDs = false
    var switch = false
    for (raw in files) {
        val p = raw.lowercase()
        if (p.startsWith("luma/") || p.startsWith("titles/") || p.startsWith("citra/") ||
            p.startsWith("azahar/") || p.endsWith(".3dsx") || p.endsWith(".cia") ||
            p.endsWith(".3ds") || p.endsWith(".cxi") || p.endsWith(".cci") ||
            Regex("(^|/)00040000[0-9a-f]{8}(/|$)").containsMatchIn(p)
        ) {
            threeDs = true
        }
        if (p.startsWith("atmosphere/") || p.startsWith("contents/") || p.startsWith("yuzu/") ||
            p.startsWith("ryujinx/") || p.endsWith(".nsp") || p.endsWith(".xci") ||
            p.endsWith(".nca") ||
            Regex("(^|/)0100[0-9a-f]{12}(/|$)").containsMatchIn(p)
        ) {
            switch = true
        }
    }
    return when {
        switch && !threeDs -> ModOrigin.MOD_SWITCH
        threeDs && !switch -> ModOrigin.MOD_3DS
        else -> ModOrigin.UNKNOWN
    }
}

fun isIgnoredFile(path: String): Boolean {
    val name = path.replace('\\', '/').substringAfterLast('/')
    return name == "enmaignore"
}

fun parseSubMods(raw: ByteArray): List<ModEntry> {
    val text = raw.toString(Charsets.UTF_8).trim()
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<ModEntry>()
    try {
        if (text.startsWith("[")) {
            val arr = org.json.JSONArray(text)
            for (i in 0 until arr.length()) {
                parseSubModLink(arr.opt(i))?.let { out += it }
            }
        } else {
            val root = org.json.JSONObject(text)
            val arr = root.optJSONArray("mods") ?: return emptyList()
            for (i in 0 until arr.length()) {
                parseSubModLink(arr.opt(i))?.let { out += it }
            }
        }
    } catch (_: Exception) {
    }
    return out
}

private fun parseSubModLink(node: Any?): ModEntry? {
    var repo = ""
    var branch = "main"
    when (node) {
        is String -> {
            repo = node.substringBefore("@").trim()
            val b = node.substringAfter("@", "").trim()
            if (b.isNotBlank()) branch = b
        }
        is org.json.JSONObject -> {
            repo = node.optString("repo").trim()
            val b = node.optString("branch").trim()
            if (b.isNotBlank()) branch = b
        }
        else -> return null
    }
    if ("/" !in repo) return null
    return ModEntry(kind = ModKind.GITHUB, repo = repo, branch = branch, enabled = true)
}
