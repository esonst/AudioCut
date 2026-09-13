package com.example.mp3player.utils

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

/**
 * 全局未捕获异常处理器
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        handleException(ex)
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, ex)
        } else {
            exitProcess(1)
        }
    }

    private fun handleException(ex: Throwable) {
        val crashInfo = collectCrashInfo(ex)
        saveCrashToFile(crashInfo)
    }

    private fun collectCrashInfo(ex: Throwable): String {
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        ex.printStackTrace(printWriter)
        
        val sb = StringBuilder()
        sb.append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Stack Trace:\n")
        sb.append(writer.toString())
        return sb.toString()
    }

    private fun saveCrashToFile(info: String) {
        try {
            val dir = context.getExternalFilesDir("crashes") ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "crash_${System.currentTimeMillis()}.log")
            file.writeText(info)
        } catch (e: Exception) {
        }
    }
}
