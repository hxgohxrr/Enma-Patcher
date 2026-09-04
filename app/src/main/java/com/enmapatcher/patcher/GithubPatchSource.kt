package com.enmapatcher.patcher

import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.EnmaCfg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    companion object {
        private const val BUFFER = 65536

        val client: OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .proxy(Proxy.NO_PROXY)
                .build()
    }
}
