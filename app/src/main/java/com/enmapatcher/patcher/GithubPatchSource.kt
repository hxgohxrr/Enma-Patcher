package com.enmapatcher.patcher

import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.EnmaCfg
import com.enmapatcher.model.ModPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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

    companion object {
        private const val BUFFER = 65536
        private const val MAX_RAW_FILE_BYTES = 512L * 1024L * 1024L
        private const val MAX_ZIP_ENTRY_BYTES = 1L * 1024L * 1024L * 1024L
        private const val ZIPBALL_MAX_BYTES = 150L * 1024L * 1024L
        private const val MAX_IN_FLIGHT_BYTES = 192L * 1024L * 1024L
        private const val TREE_CACHE_MS = 10L * 60L * 1000L
        private val treeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Map<String, Long>>>()

        val client: OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .proxy(Proxy.NO_PROXY)
                .addInterceptor { chain ->
                    val token = authToken
                    if (token.isNotBlank()) {
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Authorization", "Bearer $token")
                                .build()
                        )
                    } else {
                        chain.proceed(chain.request())
                    }
                }
                .build()

        @Volatile
        var authToken: String = ""

        private fun ensureRepoAllowed(owner: String, repo: String) {
            val target = (owner.trim() + "/" + repo.trim()).lowercase()
            val blocked = ModPolicy.DEFAULT_BANNED_WORDS.any { it.lowercase() in target }

            check(!blocked) {
                "This source is not supported."
            }
        }

        fun rawUrlFor(owner: String, repo: String, branch: String, path: String): String {
            val encoded = path.split("/").joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$encoded"
        }

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

        suspend fun fetchPatchesFor(
            owner: String,
            repo: String,
            branch: String,
            onProgress: ((done: Int, total: Int, path: String) -> Unit)?,
            spillDir: File? = null,
        ): Pair<EnmaCfg, Map<String, PatchBlob>> = withContext(Dispatchers.IO) {
            val blobs = runCatching { listRepoBlobs(owner, repo, branch) }.getOrNull()
            val total = blobs?.values?.sum() ?: Long.MAX_VALUE
            if (blobs != null && total <= ZIPBALL_MAX_BYTES) {
                val zipped = runCatching { fetchPatchesByZip(owner, repo, branch, spillDir) }.getOrNull()
                if (zipped != null && !hasLfsPointers(zipped.second)) {
                    try {
                        onProgress?.invoke(zipped.second.size, zipped.second.size, "")
                    } catch (_: Exception) {
                    }
                    return@withContext zipped
                }
            }
            val rawResult = runCatching { fetchPatchesByRaw(owner, repo, branch, onProgress, spillDir) }
            if (rawResult.isSuccess) return@withContext rawResult.getOrThrow()
            try {
                fetchPatchesByZip(owner, repo, branch, spillDir)
            } catch (_: Exception) {
                throw rawResult.exceptionOrNull() ?: IOException("PatchDownloadFailed")
            }
        }

        fun listRepoBlobs(owner: String, repo: String, branch: String): Map<String, Long> {
            ensureRepoAllowed(owner, repo)
            val key = owner.trim().lowercase() + "/" + repo.trim().lowercase() + "@" + branch
            treeCache[key]?.let { (stamp, cached) ->
                if (System.currentTimeMillis() - stamp < TREE_CACHE_MS) return cached
            }
            val url = "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"
            var lastCode = 0
            for (attempt in 1..3) {
                client.newCall(
                    Request.Builder().url(url)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                ).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val root = JSONObject(body)
                        if (root.optBoolean("truncated", false)) throw IOException("TreeTruncated")
                        val tree = root.optJSONArray("tree") ?: return emptyMap()
                        val out = LinkedHashMap<String, Long>(tree.length())
                        for (i in 0 until tree.length()) {
                            val node = tree.optJSONObject(i) ?: continue
                            if (node.optString("type") != "blob") continue
                            val path = node.optString("path").orEmpty()
                            if (path.isBlank()) continue
                            out[path] = node.optLong("size", 0L)
                        }
                        treeCache[key] = System.currentTimeMillis() to out
                        return out
                    }
                    lastCode = response.code
                }
                if ((lastCode == 403 || lastCode == 429 || lastCode >= 500) && attempt < 3) {
                    Thread.sleep(2000L * attempt * attempt)
                    continue
                }
                throw IOException("TreeListFailed:$lastCode")
            }
            throw IOException("TreeListFailed:$lastCode")
        }

        fun listRepoFiles(owner: String, repo: String, branch: String): List<String> {
            return listRepoBlobs(owner, repo, branch).keys.toList()
        }

        private fun hasLfsPointers(files: Map<String, PatchBlob>): Boolean {
            for ((_, blob) in files) {
                if (blob is PatchBlob.Mem && blob.data.size in 100..2048) {
                    if (blob.data.toString(Charsets.UTF_8).startsWith("version https://git-lfs.github.com/spec/v1")) {
                        return true
                    }
                }
            }
            return false
        }

        private fun errorCode(e: IOException): String? =
            Regex(":(\\d{3}):").find(e.message.orEmpty())?.groupValues?.getOrNull(1)

        private fun isRetryable(e: IOException): Boolean {
            if ((e.message ?: "").startsWith("RawTooLarge")) return false
            val code = errorCode(e)?.toIntOrNull() ?: return true
            return code == 408 || code == 425 || code == 429 || code >= 500
        }

        private fun shortError(path: String, e: IOException?): String {
            val code = e?.let { errorCode(it) }
            return if (code != null) "$path($code)" else path
        }

        suspend fun downloadWithRetry(
            owner: String,
            repo: String,
            branch: String,
            path: String,
            attempts: Int = 4,
            spillDir: File? = null,
        ): PatchBlob {
            var last: IOException? = null
            for (attempt in 1..attempts) {
                try {
                    return downloadRawBytes(owner, repo, branch, path, spillDir)
                } catch (e: IOException) {
                    last = e
                    if (!isRetryable(e) || attempt == attempts) break
                    delay(1000L * attempt * attempt + kotlin.random.Random.nextLong(0, 500))
                }
            }
            throw last ?: IOException("RawDownloadFailed:$path")
        }

        fun downloadRawBytes(
            owner: String,
            repo: String,
            branch: String,
            path: String,
            spillDir: File? = null,
        ): PatchBlob {
            ensureRepoAllowed(owner, repo)
            client.newCall(Request.Builder().url(rawUrlFor(owner, repo, branch, path)).build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw IOException("RawDownloadFailed:${response.code}:$path")
                    val body = response.body ?: throw IOException("RawEmpty:$path")
                    body.byteStream().use { ins ->
                        return PatchBlob.readStream(ins, MAX_RAW_FILE_BYTES, path, spillDir)
                    }
                }
        }

        suspend fun fetchPatchesByRaw(
            owner: String,
            repo: String,
            branch: String,
            onProgress: ((done: Int, total: Int, path: String) -> Unit)?,
            spillDir: File? = null,
        ): Pair<EnmaCfg, Map<String, PatchBlob>> = coroutineScope {
            ensureRepoAllowed(owner, repo)
            var config = EnmaCfg()
            try {
                fetchRemoteConfigOrNull(owner, repo, branch)?.let { config = it }
            } catch (_: Exception) {
            }
            val blobs = listRepoBlobs(owner, repo, branch)
            val targets = blobs.keys.filter { it != "enmapatcher.cfg.json" && config.allows(it) }
            val patches = java.util.concurrent.ConcurrentHashMap<String, PatchBlob>()
            val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val inFlight = java.util.concurrent.atomic.AtomicLong(0)
            val active = java.util.concurrent.atomic.AtomicInteger(0)
            val semaphore = Semaphore(8)
            val jobs = targets.map { path ->
                async {
                    semaphore.withPermit {
                        val expected = blobs[path] ?: 0L
                        while (active.get() >= 2 && inFlight.get() + expected > MAX_IN_FLIGHT_BYTES) {
                            delay(50)
                        }
                        active.incrementAndGet()
                        inFlight.addAndGet(expected)
                        try {
                            try {
                                val blob = downloadWithRetry(owner, repo, branch, path, 4, spillDir)
                                inFlight.addAndGet(blob.size() - expected)
                                patches[path] = blob
                            } catch (e: IOException) {
                                inFlight.addAndGet(-expected)
                                errors += shortError(path, e)
                            }
                        } finally {
                            active.decrementAndGet()
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
                val retryLeft = errors.toList()
                errors.clear()
                val plainLeft = retryLeft.map { it.substringBefore("(") }
                val retryTotal = targets.size + plainLeft.size
                for (path in plainLeft) {
                    try {
                        patches[path] = downloadWithRetry(owner, repo, branch, path, 4, spillDir)
                    } catch (e: IOException) {
                        errors += shortError(path, e)
                    }
                    val current = done.incrementAndGet()
                    try {
                        onProgress?.invoke(current, retryTotal, path)
                    } catch (_: Exception) {
                    }
                }
            }
            if (errors.isNotEmpty()) {
                throw IOException("RawDownloadFailed:" + errors.take(5).joinToString(","))
            }
            PatchBlob.spillDown(patches, PatchBlob.MAP_BUDGET_BYTES, spillDir)
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
            branch: String,
            spillDir: File? = null,
        ): Pair<EnmaCfg, Map<String, PatchBlob>> {
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
                val patches = mutableMapOf<String, PatchBlob>()
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
                                config = EnmaCfg.fromJson(readBounded(zis, 8L * 1024L * 1024L, name).toString(Charsets.UTF_8))
                            } else {
                                patches[relative] = PatchBlob.readStream(zis, MAX_ZIP_ENTRY_BYTES, name, spillDir)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                if (hasLfsPointers(patches)) throw IOException("LfsPointer")
                PatchBlob.spillDown(patches, PatchBlob.MAP_BUDGET_BYTES, spillDir)
                return config to patches
            }
        }

        fun loadLocalZip(inputStream: InputStream, spillDir: File? = null): Pair<EnmaCfg, Map<String, PatchBlob>> {
            val rawEntries = ArrayList<Pair<String, PatchBlob>>()
            ZipInputStream(inputStream.buffered(BUFFER)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        rawEntries += entry.name to PatchBlob.readStream(zis, MAX_ZIP_ENTRY_BYTES, entry.name, spillDir)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val stripPrefix = commonTopPrefix(rawEntries.map { it.first })
            var config = EnmaCfg()
            for ((name, blob) in rawEntries) {
                val relative = if (stripPrefix != null) name.removePrefix(stripPrefix) else name
                if (relative == "enmapatcher.cfg.json" && blob.size() <= 1L * 1024L * 1024L) {
                    config = runCatching { EnmaCfg.fromJson(blob.bytes().toString(Charsets.UTF_8)) }
                        .getOrDefault(EnmaCfg())
                    break
                }
            }
            val patches = LinkedHashMap<String, PatchBlob>(rawEntries.size)
            for ((name, blob) in rawEntries) {
                val relative = if (stripPrefix != null) name.removePrefix(stripPrefix) else name
                if (relative == "enmapatcher.cfg.json" || relative.isBlank()) continue
                if (!config.allows(relative)) continue
                patches[relative] = blob
            }
            if (patches.isEmpty() && config.appName.isNullOrBlank() && config.version.isNullOrBlank()) {
                throw IOException("EmptyModZip")
            }
            PatchBlob.spillDown(patches, PatchBlob.MAP_BUDGET_BYTES, spillDir)
            return config to patches
        }

        fun readLocalConfig(resolver: android.content.ContentResolver, uri: android.net.Uri): EnmaCfg? {
            val names = resolver.openInputStream(uri)?.use { stream ->
                val found = ArrayList<String>()
                ZipInputStream(stream.buffered(BUFFER)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) found += entry.name
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                found
            } ?: return null
            val stripPrefix = commonTopPrefix(names)
            val cfgName = (stripPrefix ?: "") + "enmapatcher.cfg.json"
            resolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream.buffered(BUFFER)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name == cfgName) {
                            return runCatching {
                                EnmaCfg.fromJson(readBounded(zis, 1L * 1024L * 1024L, entry.name).toString(Charsets.UTF_8))
                            }.getOrNull()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            return null
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
