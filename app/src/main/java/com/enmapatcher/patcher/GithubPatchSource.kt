package com.enmapatcher.patcher

import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.EnmaCfg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class GithubPatchSource(private val settings: AppSettings) {

    private val owner get() = settings.githubOwner
    private val repo get() = settings.githubRepoName
    private val branch get() = settings.githubBranch

    private fun rawUrl(path: String) =
        "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"

    private fun archiveUrl() =
        "https://api.github.com/repos/$owner/$repo/zipball/$branch"

    suspend fun fetchConfig(): EnmaCfg = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(rawUrl("enmapatcher.cfg.json")).build()).execute()
        check(response.isSuccessful) { "Config fetch failed: ${response.code}" }
        EnmaCfg.fromJson(response.body!!.string())
    }

    suspend fun fetchConfigAndPatches(): Pair<EnmaCfg, Map<String, ByteArray>> =
        withContext(Dispatchers.IO) {
            val response = client.newCall(
                Request.Builder()
                    .url(archiveUrl())
                    .header("Accept", "application/vnd.github+json")
                    .build()
            ).execute()
            check(response.isSuccessful) { "Patch download failed: ${response.code}" }

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

            config to patches
        }

    companion object {
        private const val BUFFER = 65536

        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .proxy(java.net.Proxy.NO_PROXY)
            .build()
    }
}
