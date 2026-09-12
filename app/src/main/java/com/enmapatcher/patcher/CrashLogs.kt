package com.enmapatcher.patcher

import android.content.Context
import android.content.Intent
import android.os.Build
import com.enmapatcher.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogs {

    private const val DIR = "crashlogs"
    private const val MAX_CRUMBS = 60
    private val crumbs = ArrayDeque<String>()
    private var installed = false

    fun breadcrumb(text: String) {
        synchronized(crumbs) {
            crumbs += stamped() + " " + text.take(160)
            while (crumbs.size > MAX_CRUMBS) crumbs.removeFirst()
        }
    }

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val file = runCatching { writeCrash(app, thread, error) }.getOrNull()
            runCatching {
                val intent = Intent(app, Class.forName("com.enmapatcher.CrashActivity")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    if (file != null) putExtra("crash_path", file.absolutePath)
                }
                app.startActivity(intent)
            }
            try {
                old?.uncaughtException(thread, error)
            } catch (_: Exception) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIR).also { it.mkdirs() }
    }

    fun writeCrash(context: Context, thread: Thread, error: Throwable): File {
        val body = buildString {
            appendLine("EnmaPatcher crash log")
            appendLine("time=" + stamped())
            appendLine("app=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
            appendLine("android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT)
            appendLine("device=" + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("thread=" + thread.name)
            appendLine("breadcrumbs=" + synchronized(crumbs) { crumbs.size })
            synchronized(crumbs) {
                for (c in crumbs) appendLine("crumb: " + c)
            }
            var ex: Throwable? = error
            while (ex != null) {
                appendLine("cause: " + ex.javaClass.name + ": " + ex.message)
                for (line in ex.stackTrace.take(40)) appendLine("    at " + line.toString())
                ex = ex.cause
            }
        }
        val dest = File(dir(context), "crash_" + stamp() + ".txt")
        dest.writeText(body)
        prune(dir(context))
        return dest
    }

    fun writePatchLog(context: Context, message: String, summary: String): File {
        val body = buildString {
            appendLine("EnmaPatcher patch log")
            appendLine("time=" + stamped())
            appendLine("app=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
            appendLine("android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT)
            appendLine("device=" + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("summary=" + summary)
            synchronized(crumbs) {
                for (c in crumbs) appendLine("crumb: " + c)
            }
            appendLine("error=" + message)
        }
        val dest = File(dir(context), "patch_" + stamp() + ".log")
        dest.writeText(body)
        prune(dir(context))
        return dest
    }

    fun pending(context: Context): File? {
        return try {
            dir(context).listFiles { f -> f.isFile && (f.name.startsWith("crash_") || f.name.startsWith("patch_")) }
                ?.maxByOrNull { it.lastModified() }
        } catch (_: Exception) {
            null
        }
    }

    private fun prune(directory: File) {
        try {
            val files = directory.listFiles { f ->
                f.isFile && (f.name.startsWith("crash_") || f.name.startsWith("patch_"))
            }?.sortedByDescending { it.lastModified() } ?: return
            for (extra in files.drop(20)) runCatching { extra.delete() }
        } catch (_: Exception) {
        }
    }

    private fun stamped(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    private fun stamp(): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }
}
