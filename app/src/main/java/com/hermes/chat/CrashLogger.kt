package com.hermes.chat

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常捕获器：把崩溃栈写到 App 私有目录的 crash.log，
 * 方便在无 adb 的真机环境下复现并定位问题。
 * 仅额外落盘，不吞掉系统默认崩溃行为。
 */
class CrashLogger private constructor(private val context: Context) :
    Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "crash.log")
            FileWriter(file, true).use { writer ->
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                writer.append("==== crash at ${sdf.format(Date())} ====\n")
                writer.append("SDK=${Build.VERSION.SDK_INT} model=${Build.MODEL} release=${Build.VERSION.RELEASE}\n")
                throwable.printStackTrace(PrintWriter(writer))
                writer.append("\n")
            }
        } catch (e: Exception) {
            Log.e("CrashLogger", "failed to write crash log", e)
        } finally {
            // 交给系统默认处理，保持"应用已停止"的原生表现
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun install(context: Context) {
            val logger = CrashLogger(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(logger)
        }
    }
}
