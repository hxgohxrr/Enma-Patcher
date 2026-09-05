package com.enmapatcher.patcher

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object RootShell {

    fun available(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        out.contains("uid=0")
    } catch (_: Exception) {
        false
    }

    fun run(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) throw IllegalStateException(err.ifBlank { out }.ifBlank { "exit=$code" })
        return out
    }
}

class SaveManager(private val context: Context) {

    data class SaveDir(val path: String, val rooted: Boolean, val files: List<String>)

    data class SaveZip(val files: List<String>, val totalCount: Int, val truncated: Boolean)

    data class ImportResult(
        val applied: List<String>,
        val skipped: List<String>,
        val backupZip: File?,
    )

    suspend fun locate(packageName: String): List<SaveDir> = withContext(Dispatchers.IO) {
        val out = mutableListOf<SaveDir>()
        val extRoots = listOf(
            File("/storage/emulated/0/Android/data", packageName),
            File("/sdcard/Android/data", packageName),
        )
        for (root in extRoots) {
            runCatching { findSaveChild(root, 5) }.getOrNull()?.let { dir ->
                out += SaveDir(
                    dir.absolutePath,
                    false,
                    dir.list()?.toList()?.sorted() ?: emptyList(),
                )
            }
        }
        if (RootShell.available()) {
            runCatching {
                val found = RootShell.run(
                    "for d in /data/data/$packageName /data/user/0/$packageName;" +
                        " do [ -d \"\$d\" ] && find \"\$d\" -maxdepth 6 -type d -name save 2>/dev/null; done"
                )
                found.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { dir ->
                    val files = runCatching {
                        RootShell.run("ls -1 \"$dir\"").lineSequence()
                            .map { it.trim() }.filter { it.isNotEmpty() }.toList().sorted()
                    }.getOrDefault(emptyList())
                    if (files.any { it in SAVE_FILES }) out += SaveDir(dir, true, files)
                }
            }
        }
        out.distinctBy { it.path }
    }

    private fun findSaveChild(root: File, maxDepth: Int): File? {
        if (!root.isDirectory || !root.canRead()) return null
        val queue = ArrayDeque<Pair<File, Int>>()
        queue += root to 0
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (dir.name == "save" && hasSaveFile(dir)) return dir
            if (depth >= maxDepth) continue
            dir.listFiles()?.filter { it.isDirectory }?.forEach { queue += it to depth + 1 }
        }
        return null
    }

    private fun hasSaveFile(dir: File): Boolean {
        val names = dir.list()?.toSet() ?: return false
        return names.any { it in SAVE_FILES }
    }

    suspend fun exportToZip(saveDir: SaveDir): File = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "save_export_${System.currentTimeMillis()}")
        staging.mkdirs()
        if (saveDir.rooted) {
            RootShell.run("cp -r \"${saveDir.path}/.\" \"${staging.absolutePath}/\"")
            RootShell.run("chmod -R a+r \"${staging.absolutePath}\"")
        } else {
            File(saveDir.path).copyRecursively(staging, overwrite = true)
        }
        val destDir = context.getExternalFilesDir("saves") ?: File(context.filesDir, "saves")
        destDir.mkdirs()
        val dest = File(destDir, "enma_save_${System.currentTimeMillis()}.zip")
        ZipOutputStream(dest.outputStream().buffered()).use { zos ->
            staging.walkTopDown().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                zos.putNextEntry(ZipEntry(file.relativeTo(staging).path.replace('\\', '/')))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        staging.deleteRecursively()
        dest
    }

    suspend fun previewZip(uri: Uri, limit: Int = 200): SaveZip = withContext(Dispatchers.IO) {
        val names = mutableListOf<String>()
        var total = 0
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        total++
                        if (names.size < limit) names += normalize(entry.name)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("ZipOpenFailed")
        SaveZip(names.distinct().sorted(), total, total > names.size)
    }

    suspend fun importFromZip(saveDir: SaveDir, uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            val staging = File(context.cacheDir, "save_import_${System.currentTimeMillis()}")
            staging.mkdirs()
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(stream.buffered()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val name = normalize(entry.name)
                                if (name.isNotBlank() && !name.contains("..")) {
                                    val out = File(staging, name)
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { zis.copyTo(it) }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } ?: throw IllegalArgumentException("ZipOpenFailed")
                val backup = backupSaveDir(saveDir)
                val applied = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                for (name in SAVE_FILES) {
                    val src = File(staging, name)
                    if (!src.isFile) {
                        skipped += name
                        continue
                    }
                    writeFile(saveDir, name, src.readBytes())
                    applied += name
                }
                staging.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(staging).path.replace('\\', '/')
                    if (rel !in SAVE_FILES && rel !in applied && rel !in skipped) skipped += rel
                }
                ImportResult(applied.sorted(), skipped.distinct().sorted(), backup)
            } finally {
                staging.deleteRecursively()
            }
        }

    private fun backupSaveDir(saveDir: SaveDir): File? {
        return try {
            val staging = File(context.cacheDir, "save_backup_${System.currentTimeMillis()}")
            staging.mkdirs()
            try {
                if (saveDir.rooted) {
                    RootShell.run("cp -r \"${saveDir.path}/.\" \"${staging.absolutePath}/\"")
                    RootShell.run("chmod -R a+r \"${staging.absolutePath}\"")
                } else {
                    File(saveDir.path).copyRecursively(staging, overwrite = true)
                }
                val destDir =
                    context.getExternalFilesDir("backups") ?: File(context.filesDir, "backups")
                destDir.mkdirs()
                val dest = File(destDir, "save_backup_${System.currentTimeMillis()}.zip")
                ZipOutputStream(dest.outputStream().buffered()).use { zos ->
                    staging.walkTopDown().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                        zos.putNextEntry(ZipEntry(file.relativeTo(staging).path.replace('\\', '/')))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                dest
            } finally {
                staging.deleteRecursively()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeFile(saveDir: SaveDir, name: String, bytes: ByteArray) {
        if (saveDir.rooted) {
            val tmp = File(context.cacheDir, "save_write_${System.currentTimeMillis()}_$name")
            try {
                tmp.writeBytes(bytes)
                RootShell.run("cp \"${tmp.absolutePath}\" \"${saveDir.path}/$name\"")
                RootShell.run("chmod 660 \"${saveDir.path}/$name\"")
            } finally {
                tmp.delete()
            }
        } else {
            File(saveDir.path, name).writeBytes(bytes)
        }
    }

    private fun normalize(raw: String): String {
        var name = raw.replace('\\', '/').trimStart('/')
        for (prefix in listOf("sram/save/", "save/")) {
            if (name.startsWith(prefix)) {
                name = name.removePrefix(prefix)
                break
            }
        }
        return name
    }

    companion object {
        val SAVE_FILES = listOf("head.yw", "game0.yw", "game1.yw", "game2.yw")
        val CONTAINER_FILES = listOf("main.bin", "stage.bin", "backup.bin", "temp.bin")
        const val EXCLUDED_IMPORT = "main.bin"
    }
}
