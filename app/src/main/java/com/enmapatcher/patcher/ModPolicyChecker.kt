package com.enmapatcher.patcher

import com.enmapatcher.model.ModPolicy

class ModPolicyChecker(private val policyUrl: String) {

    fun loadPolicy(): ModPolicy {
        val url = policyUrl.ifBlank { ModPolicy.DEFAULT_URL }
        val raw = GithubPatchSource.fetchRawText(url) ?: return ModPolicy()
        return ModPolicy.fromJson(raw)
    }

    fun checkRepo(policy: ModPolicy, repo: String) {
        val blocked = policy.findBlockedRepo(repo)
        if (blocked != null) throw SecurityException("BlockedRepo:$blocked")
    }

    fun checkPaths(policy: ModPolicy, paths: Iterable<String>) {
        for (path in paths) {
            val blockedPath = policy.findBlockedPath(path)
            if (blockedPath != null) throw SecurityException("BlockedPath:$blockedPath:$path")
            val blockedWord = policy.findBlockedWord(path)
            if (blockedWord != null) throw SecurityException("BlockedWord:$blockedWord:$path")
        }
    }

    fun checkContents(policy: ModPolicy, files: Map<String, PatchBlob>) {
        if (policy.bannedWords.isEmpty()) return
        for ((path, blob) in files) {
            val head = when (blob) {
                is PatchBlob.Mem -> {
                    if (blob.data.size > 262_144 || blob.data.contains(0)) continue
                    blob.data
                }
                is PatchBlob.Disk -> {
                    if (blob.file.length() > 262_144) continue
                    val probe = ByteArray(65536)
                    val read = runCatching {
                        blob.file.inputStream().use { it.read(probe) }
                    }.getOrNull() ?: continue
                    if (read <= 0 || probe.copyOf(read).contains(0)) continue
                    probe.copyOf(read)
                }
            }
            val text = runCatching { head.toString(Charsets.UTF_8) }.getOrNull() ?: continue
            if (!text.any { it.isLetterOrDigit() }) continue
            val blocked = policy.findBlockedWord(text)
            if (blocked != null) throw SecurityException("BlockedWord:$blocked:$path")
        }
    }
}
