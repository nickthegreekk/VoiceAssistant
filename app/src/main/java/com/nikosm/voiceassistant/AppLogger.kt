package com.nikosm.voiceassistant

import android.util.Log

/**
 * Where this application logs — the port of the Linux build's `AppLogger.kt`.
 *
 * The Linux tree replaced `android.util.Log` with an interface so that injected code could carry a
 * logger. **This tree is the original Android app**, so `android.util.Log` is what the rest of the
 * code already calls — and the ported MCP files are the only ones that need a logger they can be
 * handed. Rather than rewriting every one of those call sites into `android.util.Log`, this shim
 * gives them the same six named levels the Linux tree had, and maps them onto Logcat.
 *
 * The tag rides in the message, exactly as the Linux tree did: Logcat already prints the tag as a
 * column, but the ported files prefix their messages with it in places, and a doubled tag is
 * harmless where a missing one is not.
 */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, ASSERT }

interface AppLogger {
    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null)

    fun v(tag: String, message: String, error: Throwable? = null) = log(LogLevel.VERBOSE, tag, message, error)
    fun d(tag: String, message: String, error: Throwable? = null) = log(LogLevel.DEBUG, tag, message, error)
    fun i(tag: String, message: String, error: Throwable? = null) = log(LogLevel.INFO, tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)
    fun wtf(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ASSERT, tag, message, error)
}

/**
 * The one sink: Logcat.
 *
 * `VERBOSE` maps to Logcat's `VERBOSE`, which is the only level Logcat has that the other five do
 * not, and `ASSERT` maps to `ASSERT` as well. The mapping is a value rather than a switch so that
 * a build which wants to drop VERBOSE has exactly one place to do it.
 */
object AndroidAppLogger : AppLogger {
    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val line = "[$tag] $message"
        when (level) {
            LogLevel.VERBOSE -> if (error == null) Log.v(tag, line) else Log.v(tag, line, error)
            LogLevel.DEBUG -> if (error == null) Log.d(tag, line) else Log.d(tag, line, error)
            LogLevel.INFO -> if (error == null) Log.i(tag, line) else Log.i(tag, line, error)
            LogLevel.WARN -> if (error == null) Log.w(tag, line) else Log.w(tag, line, error)
            LogLevel.ERROR -> if (error == null) Log.e(tag, line) else Log.e(tag, line, error)
            LogLevel.ASSERT -> if (error == null) Log.wtf(tag, line) else Log.wtf(tag, line, error)
        }
    }
}
