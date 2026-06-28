package com.storage.redirect.x.util

import android.util.Log

// 统一日志工具，所有模块通过此对象输出日志
object Logger {
    private const val LOGCAT_CHANNEL = "StorageRedirectKt"

    fun debug(message: String) {
        log(Log.DEBUG, message)
    }

    fun info(message: String) {
        log(Log.INFO, message)
    }

    fun warn(message: String) {
        log(Log.WARN, message)
    }

    fun error(message: String) {
        log(Log.ERROR, message)
    }

    fun error(message: String, throwable: Throwable) {
        log(Log.ERROR, message, throwable)
    }

    private fun log(priority: Int, message: String, throwable: Throwable? = null) {
        if (message.isEmpty()) return
        val output = if (throwable == null) {
            message
        } else {
            "$message\n${Log.getStackTraceString(throwable)}"
        }
        val prefixed = "[Kt${levelText(priority)}] $output"
        Log.println(priority, LOGCAT_CHANNEL, prefixed)
    }

    private fun levelText(priority: Int): String {
        return when (priority) {
            Log.VERBOSE -> "Verbose"
            Log.DEBUG -> "Debug"
            Log.INFO -> "Info"
            Log.WARN -> "Warn"
            Log.ERROR -> "Error"
            Log.ASSERT -> "Fatal"
            else -> "Info"
        }
    }
}
