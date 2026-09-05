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
