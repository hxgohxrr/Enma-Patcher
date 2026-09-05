package com.enmapatcher.patcher

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.util.zip.ZipFile











class SmaliDexPatcher {







    fun buildDexPatches(
        baseApk: File,
        smaliPatches: Map<String, ByteArray>,
        workDir: File,
    ): Map<String, ByteArray> {
        if (smaliPatches.isEmpty()) return emptyMap()



        val smaliInput = File(workDir, "smali_input").also { it.mkdirs() }
        for ((path, bytes) in smaliPatches) {
            val rel = path.removePrefix("smali/")
            val out = File(smaliInput, rel)
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        }


        workDir.mkdirs()
        val compiledDex = File(workDir, "smali_patched.dex")
        compiledDex.delete()
        val opts = SmaliOptions().apply {
            apiLevel = 32
            outputDexFile = compiledDex.canonicalPath
        }

        val ok = Smali.assemble(opts, smaliInput.canonicalPath)
        if (!ok || !compiledDex.exists()) {
            compiledDex.delete()
            return emptyMap()
        }

        val opcodes = Opcodes.forApi(32)
        val patchedClasses = DexFileFactory.loadDexFile(compiledDex, opcodes)
            .classes.associateBy { it.type }


        val result = mutableMapOf<String, ByteArray>()
        ZipFile(baseApk).use { apk ->
            for (entry in apk.entries()) {
                if (!entry.name.matches(Regex("classes\\d*\\.dex"))) continue

                val origBytes = apk.getInputStream(entry).readBytes()
                val origTmp = File(workDir, "_orig_${entry.name}").also { it.writeBytes(origBytes) }
                val origDex = DexFileFactory.loadDexFile(origTmp, opcodes)

                if (origDex.classes.none { it.type in patchedClasses }) continue


                val pool = DexPool(opcodes)
                for (cls in origDex.classes) {
                    pool.internClass(patchedClasses[cls.type] ?: cls)
                }
                val outFile = File(workDir, "merged_${entry.name}")
                pool.writeTo(org.jf.dexlib2.writer.io.FileDataStore(outFile))
                result[entry.name] = outFile.readBytes()
            }
        }

        compiledDex.delete()
        return result
    }
}
