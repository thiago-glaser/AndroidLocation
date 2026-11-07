package com.example.locationservice

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object AppLogger {
    private const val MAX_FILE_BYTES = 512 * 1024L // 512 KB
    private const val MAX_MEMORY_LINES = 1000
    private const val LOG_FILE_NAME = "app_diagnostic.log"
    private const val OLD_LOG_FILE_NAME = "app_diagnostic.old.log"

    @Volatile
    private var appContext: Context? = null
    private val memoryLogs = ConcurrentLinkedDeque<String>()
    private val fileLock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun formatLine(level: String, tag: String, message: String, tr: Throwable? = null): String {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val pid = android.os.Process.myPid()
        val base = "$ts $pid $level/$tag: $message"
        return if (tr != null) {
            "$base\n${Log.getStackTraceString(tr).trimEnd()}"
        } else {
            base
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        record("D", tag, message, null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        record("I", tag, message, null)
    }

    fun w(tag: String, message: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, message, tr) else Log.w(tag, message)
        record("W", tag, message, tr)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, message, tr) else Log.e(tag, message)
        record("E", tag, message, tr)
    }

    private fun record(level: String, tag: String, message: String, tr: Throwable?) {
        val line = formatLine(level, tag, message, tr)
        memoryLogs.addLast(line)
        while (memoryLogs.size > MAX_MEMORY_LINES) {
            memoryLogs.pollFirst()
        }

        val ctx = appContext ?: return
        synchronized(fileLock) {
            try {
                val file = File(ctx.filesDir, LOG_FILE_NAME)
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    val oldFile = File(ctx.filesDir, OLD_LOG_FILE_NAME)
                    if (oldFile.exists()) oldFile.delete()
                    file.renameTo(oldFile)
                }
                FileOutputStream(File(ctx.filesDir, LOG_FILE_NAME), true).use { fos ->
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {
            }
        }
    }

    fun readCombinedLogs(context: Context, maxLines: Int = 1500): List<String> {
        init(context)
        val combined = LinkedHashSet<String>()

        // 1. Read persistent logs (survives app updates and process restarts)
        synchronized(fileLock) {
            try {
                val oldFile = File(context.filesDir, OLD_LOG_FILE_NAME)
                if (oldFile.exists()) {
                    oldFile.readLines(Charsets.UTF_8).takeLast(maxLines / 2).forEach {
                        if (it.isNotBlank()) combined.add(it)
                    }
                }
                val file = File(context.filesDir, LOG_FILE_NAME)
                if (file.exists()) {
                    file.readLines(Charsets.UTF_8).takeLast(maxLines).forEach {
                        if (it.isNotBlank()) combined.add(it)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 2. Include in-memory logs
        memoryLogs.forEach { combined.add(it) }

        // 3. Read live Android OS logcat buffer for this app
        try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", maxLines.toString())
                .redirectErrorStream(true)
                .start()
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.isNotBlank() && !line.startsWith("--------- beginning of")) {
                        combined.add(line)
                    }
                }
            }
        } catch (_: Exception) {
        }

        val list = combined.toList()
        return if (list.size > maxLines) list.takeLast(maxLines) else list
    }

    fun clearLogs(context: Context) {
        init(context)
        memoryLogs.clear()
        synchronized(fileLock) {
            try {
                File(context.filesDir, OLD_LOG_FILE_NAME).delete()
                File(context.filesDir, LOG_FILE_NAME).delete()
            } catch (_: Exception) {
            }
        }
        try {
            ProcessBuilder("logcat", "-c").start().waitFor()
        } catch (_: Exception) {
        }
    }
}

