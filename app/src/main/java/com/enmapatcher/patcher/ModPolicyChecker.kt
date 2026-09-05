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

    fun checkContents(policy: ModPolicy, files: Map<String, ByteArray>) {
        if (policy.bannedWords.isEmpty()) return
        for ((path, bytes) in files) {
            if (bytes.size > 1_048_576) continue
            val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: continue
            if (!text.any { it.isLetterOrDigit() }) continue
            val blocked = policy.findBlockedWord(text)
            if (blocked != null) throw SecurityException("BlockedWord:$blocked:$path")
        }
    }
}
