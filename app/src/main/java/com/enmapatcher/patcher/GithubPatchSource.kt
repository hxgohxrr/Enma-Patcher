package com.enmapatcher.patcher

import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.EnmaCfg
import com.enmapatcher.model.ModPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        val target = (owner.trim() + "/" + repo.trim()).lowercase()
        val blocked = ModPolicy.DEFAULT_BANNED_WORDS.any { it.lowercase() in target }

        check(!blocked) {
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
        private const val MAX_RAW_FILE_BYTES = 512L * 1024L * 1024L
        private const val MAX_ZIP_ENTRY_BYTES = 1L * 1024L * 1024L * 1024L

        val client: OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .proxy(Proxy.NO_PROXY)
                .build()

        private fun ensureRepoAllowed(owner: String, repo: String) {
            val target = (owner.trim() + "/" + repo.trim()).lowercase()
            val blocked = ModPolicy.DEFAULT_BANNED_WORDS.any { it.lowercase() in target }

            check(!blocked) {
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

        suspend fun fetchPatchesByRaw(
            owner: String,
            repo: String,
            branch: String,
            onProgress: ((done: Int, total: Int, path: String) -> Unit)?
        ): Pair<EnmaCfg, Map<String, ByteArray>> = coroutineScope {
            ensureRepoAllowed(owner, repo)
            var config = EnmaCfg()
            try {
                fetchRemoteConfigOrNull(owner, repo, branch)?.let { config = it }
            } catch (_: Exception) {
            }
            val files = listRepoFiles(owner, repo, branch)
            val targets = files.filter { it != "enmapatcher.cfg.json" && config.allows(it) }
            val patches = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
            val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val semaphore = Semaphore(4)
            val jobs = targets.map { path ->
                async {
                    semaphore.withPermit {
                        var attempt = 0
                        while (true) {
                            try {
                                patches[path] = downloadRawBytes(owner, repo, branch, path)
                                break
                            } catch (e: IOException) {
                                if (e.message?.startsWith("RawTooLarge") == true) throw e
                                attempt++
                                if (attempt > 1) {
                                    errors += path
                                    break
                                }
                            }
                        }
                        val current = done.incrementAndGet()
                        try {
                            onProgress?.invoke(current, targets.size, path)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            jobs.awaitAll()
            if (patches.isEmpty() && targets.isNotEmpty()) throw IOException("RawDownloadEmpty")
            if (errors.isNotEmpty()) {
                throw IOException("RawDownloadFailed:" + errors.take(5).joinToString(","))
            }
            config to patches
        }

        fun readBounded(stream: java.io.InputStream, max: Long, label: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(65536)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw IOException("EntryTooLarge:$label")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
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
            val rawEntries = ArrayList<Pair<String, ByteArray>>()
            ZipInputStream(inputStream.buffered(BUFFER)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        rawEntries += entry.name to readBounded(zis, MAX_ZIP_ENTRY_BYTES, entry.name)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val stripPrefix = commonTopPrefix(rawEntries.map { it.first })
            var config = EnmaCfg()
            for ((name, bytes) in rawEntries) {
                val relative = if (stripPrefix != null) name.removePrefix(stripPrefix) else name
                if (relative == "enmapatcher.cfg.json") {
                    config = runCatching { EnmaCfg.fromJson(bytes.toString(Charsets.UTF_8)) }
                        .getOrDefault(EnmaCfg())
                    break
                }
            }
            val patches = LinkedHashMap<String, ByteArray>(rawEntries.size)
            for ((name, bytes) in rawEntries) {
                val relative = if (stripPrefix != null) name.removePrefix(stripPrefix) else name
                if (relative == "enmapatcher.cfg.json" || relative.isBlank()) continue
                if (!config.allows(relative)) continue
                patches[relative] = bytes
            }
            if (patches.isEmpty() && config.appName.isNullOrBlank() && config.version.isNullOrBlank()) {
                throw IOException("EmptyModZip")
            }
            return config to patches
        }

        fun listLocalZipEntries(inputStream: InputStream, limit: Int = 500): List<String> {
            val names = ArrayList<String>()
            ZipInputStream(inputStream.buffered(BUFFER)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) names += entry.name
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val stripPrefix = commonTopPrefix(names)
            val out = names.map { if (stripPrefix != null) it.removePrefix(stripPrefix) else it }
                .filter { it.isNotBlank() }
            return out.sorted().take(limit)
        }

        private fun commonTopPrefix(names: List<String>): String? {
            if (names.isEmpty()) return null
            val tops = names.map { it.substringBefore("/") }
            val top = tops.firstOrNull() ?: return null
            if ("/" !in names.first()) return null
            if (top.isBlank() || tops.any { it != top }) return null
            if (names.any { it == top }) return null
            return "$top/"
        }
    }
}
