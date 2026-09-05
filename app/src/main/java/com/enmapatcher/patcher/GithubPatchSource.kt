package com.enmapatcher.patcher

import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.EnmaCfg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class GithubPatchSource(private val settings: AppSettings) {

    private val owner: String
        get() = settings.githubOwner

    private val repo: String
        get() = settings.githubRepoName

    private val branch: String
        get() = settings.githubBranch

    private fun ensureSourceAllowed() {
        val ownerNormalized = owner.trim().lowercase()
        val repoNormalized = repo.trim().lowercase()

        val blockedOwner = ownerNormalized == "raizuma"
        val blockedRepository =
            ownerNormalized.contains("raizuma") ||
            repoNormalized.contains("raizuma")

        check(!blockedOwner && !blockedRepository) {
            "This source is not supported."
        }
    }

    private fun rawUrl(path: String): String {
        ensureSourceAllowed()

        return "https://raw.githubusercontent.com/" +
                "$owner/$repo/$branch/$path"
    }

    private fun archiveUrl(): String {
        ensureSourceAllowed()

        return "https://api.github.com/repos/" +
                "$owner/$repo/zipball/$branch"
    }

    suspend fun fetchConfig(): EnmaCfg = withContext(Dispatchers.IO) {
        ensureSourceAllowed()

        val response = client.newCall(
            Request.Builder()
                .url(rawUrl("enmapatcher.cfg.json"))
                .build()
        ).execute()

        response.use {
            check(it.isSuccessful) {
                "Config fetch failed: ${it.code}"
            }

            EnmaCfg.fromJson(
                it.body?.string()
                    ?: error("Empty config response")
            )
        }
    }

    suspend fun fetchConfigAndPatches():
            Pair<EnmaCfg, Map<String, ByteArray>> =
        withContext(Dispatchers.IO) {

            ensureSourceAllowed()

            val response = client.newCall(
                Request.Builder()
                    .url(archiveUrl())
                    .header("Accept", "application/vnd.github+json")
                    .build()
            ).execute()

            response.use {
                check(it.isSuccessful) {
                    "Patch download failed: ${it.code}"
                }

                var config = EnmaCfg()
                val patches = mutableMapOf<String, ByteArray>()

                ZipInputStream(
                    it.body?.byteStream()?.buffered(BUFFER)
                        ?: error("Empty patch response")
                ).use { zis ->

                    var entry = zis.nextEntry
                    var stripPrefix: String? = null

                    while (entry != null) {
                        val name = entry.name

                        if (
                            stripPrefix == null &&
                            name.endsWith("/") &&
                            name.count { char -> char == '/' } == 1
                        ) {
                            stripPrefix = name
                            entry = zis.nextEntry
                            continue
                        }

                        if (!entry.isDirectory) {
                            val relative =
                                if (
                                    stripPrefix != null &&
                                    name.startsWith(stripPrefix)
                                ) {
                                    name.removePrefix(stripPrefix)
                                } else {
                                    name
                                }

                            if (relative == "enmapatcher.cfg.json") {
                                config = EnmaCfg.fromJson(
                                    zis.readBytes()
                                        .toString(Charsets.UTF_8)
                                )
                            } else {
                                patches[relative] = zis.readBytes()
                            }
                        }

                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                config to patches
            }
        }

    suspend fun fetchPatchesFor(
        owner: String,
        repo: String,
        branch: String,
        onProgress: ((done: Int, total: Int, path: String) -> Unit)?
    ): Pair<EnmaCfg, Map<String, ByteArray>> = withContext(Dispatchers.IO) {
        val rawResult = runCatching { fetchPatchesByRaw(owner, repo, branch, onProgress) }
        if (rawResult.isSuccess) return@withContext rawResult.getOrThrow()
        try {
            fetchPatchesByZip(owner, repo, branch)
        } catch (_: Exception) {
            throw rawResult.exceptionOrNull() ?: IOException("PatchDownloadFailed")
        }
    }

    companion object {
        private const val BUFFER = 65536
        private const val MAX_RAW_FILE_BYTES = 200L * 1024L * 1024L

        val client: OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .proxy(Proxy.NO_PROXY)
                .build()

        private fun ensureRepoAllowed(owner: String, repo: String) {
            val ownerNormalized = owner.trim().lowercase()
            val repoNormalized = repo.trim().lowercase()

            val blockedOwner = ownerNormalized == "raizuma"
            val blockedRepository =
                ownerNormalized.contains("raizuma") ||
                repoNormalized.contains("raizuma")

            check(!blockedOwner && !blockedRepository) {
                "This source is not supported."
            }
        }

        fun rawUrlFor(owner: String, repo: String, branch: String, path: String): String =
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"

        fun fetchRawText(url: String): String? {
            return try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return null
                    response.body?.string()
                }
            } catch (_: Exception) {
                null
            }
        }

        fun fetchRemoteConfigOrNull(owner: String, repo: String, branch: String): EnmaCfg? {
            return try {
                ensureRepoAllowed(owner, repo)
                val text = fetchRawText(rawUrlFor(owner, repo, branch, "enmapatcher.cfg.json"))
                    ?: return null
                EnmaCfg.fromJson(text)
            } catch (_: Exception) {
                null
            }
        }

        fun listRepoFiles(owner: String, repo: String, branch: String): List<String> {
            ensureRepoAllowed(owner, repo)
            val url = "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"
            client.newCall(
                Request.Builder().url(url)
                    .header("Accept", "application/vnd.github+json")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) throw IOException("TreeListFailed:${response.code}")
                val body = response.body?.string().orEmpty()
                val root = JSONObject(body)
                if (root.optBoolean("truncated", false)) throw IOException("TreeTruncated")
                val tree = root.optJSONArray("tree") ?: return emptyList()
                val out = ArrayList<String>(tree.length())
                for (i in 0 until tree.length()) {
                    val node = tree.optJSONObject(i) ?: continue
                    if (node.optString("type") != "blob") continue
                    val path = node.optString("path").orEmpty()
                    if (path.isBlank()) continue
                    out += path
                }
                return out
            }
        }

        fun downloadRawBytes(owner: String, repo: String, branch: String, path: String): ByteArray {
            ensureRepoAllowed(owner, repo)
            client.newCall(Request.Builder().url(rawUrlFor(owner, repo, branch, path)).build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw IOException("RawDownloadFailed:${response.code}:$path")
                    val source = response.body?.source() ?: throw IOException("RawEmpty:$path")
                    val sink = okio.Buffer()
                    var total = 0L
                    while (true) {
                        val read = source.read(sink, 65536L)
                        if (read == -1L) break
                        total += read
                        if (total > MAX_RAW_FILE_BYTES) throw IOException("RawTooLarge:$path")
                    }
                    return sink.readByteArray()
                }
        }

        fun fetchPatchesByRaw(
            owner: String,
            repo: String,
            branch: String,
            onProgress: ((done: Int, total: Int, path: String) -> Unit)?
        ): Pair<EnmaCfg, Map<String, ByteArray>> {
            ensureRepoAllowed(owner, repo)
            var config = EnmaCfg()
            try {
                fetchRemoteConfigOrNull(owner, repo, branch)?.let { config = it }
            } catch (_: Exception) {
            }
            val files = listRepoFiles(owner, repo, branch)
            val targets = files.filter { it != "enmapatcher.cfg.json" }
            val patches = LinkedHashMap<String, ByteArray>(targets.size)
            var done = 0
            for (path in targets) {
                try {
                    patches[path] = downloadRawBytes(owner, repo, branch, path)
                } catch (_: Exception) {
                }
                done++
                try {
                    onProgress?.invoke(done, targets.size, path)
                } catch (_: Exception) {
                }
            }
            if (patches.isEmpty() && targets.isNotEmpty()) throw IOException("RawDownloadEmpty")
            return config to patches
        }

        fun fetchPatchesByZip(
            owner: String,
            repo: String,
            branch: String
        ): Pair<EnmaCfg, Map<String, ByteArray>> {
            ensureRepoAllowed(owner, repo)
            val url = "https://api.github.com/repos/$owner/$repo/zipball/$branch"
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) throw IOException("PatchDownloadFailed:${response.code}")
                var config = EnmaCfg()
                val patches = mutableMapOf<String, ByteArray>()
                ZipInputStream(response.body!!.byteStream().buffered(BUFFER)).use { zis ->
                    var entry = zis.nextEntry
                    var stripPrefix: String? = null
                    while (entry != null) {
                        val name = entry.name
                        if (stripPrefix == null && name.endsWith("/") && name.count { it == '/' } == 1) {
                            stripPrefix = name
                            entry = zis.nextEntry
                            continue
                        }
                        if (!entry.isDirectory) {
                            val relative = if (stripPrefix != null && name.startsWith(stripPrefix))
                                name.removePrefix(stripPrefix) else name
                            if (relative == "enmapatcher.cfg.json") {
                                config = EnmaCfg.fromJson(zis.readBytes().toString(Charsets.UTF_8))
                            } else {
                                patches[relative] = zis.readBytes()
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                return config to patches
            }
        }

        fun loadLocalZip(inputStream: InputStream): Pair<EnmaCfg, Map<String, ByteArray>> {
            var config = EnmaCfg()
            val patches = mutableMapOf<String, ByteArray>()
            ZipInputStream(inputStream.buffered(BUFFER)).use { zis ->
                var entry = zis.nextEntry
                var stripPrefix: String? = null
                while (entry != null) {
                    val name = entry.name
                    if (stripPrefix == null && name.endsWith("/") && name.count { it == '/' } == 1) {
                        stripPrefix = name
                        entry = zis.nextEntry
                        continue
                    }
                    if (!entry.isDirectory) {
                        val relative = if (stripPrefix != null && name.startsWith(stripPrefix))
                            name.removePrefix(stripPrefix) else name
                        if (relative == "enmapatcher.cfg.json") {
                            config = EnmaCfg.fromJson(zis.readBytes().toString(Charsets.UTF_8))
                        } else {
                            patches[relative] = zis.readBytes()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            return config to patches
        }

        fun listLocalZipEntries(inputStream: InputStream, limit: Int = 500): List<String> {
            val out = ArrayList<String>()
            ZipInputStream(inputStream.buffered(BUFFER)).use { zis ->
                var entry = zis.nextEntry
                var stripPrefix: String? = null
                while (entry != null && out.size < limit) {
                    val name = entry.name
                    if (stripPrefix == null && name.endsWith("/") && name.count { it == '/' } == 1) {
                        stripPrefix = name
                        entry = zis.nextEntry
                        continue
                    }
                    if (!entry.isDirectory) {
                        val relative = if (stripPrefix != null && name.startsWith(stripPrefix))
                            name.removePrefix(stripPrefix) else name
                        out += relative
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            return out.sorted()
        }
    }
}
