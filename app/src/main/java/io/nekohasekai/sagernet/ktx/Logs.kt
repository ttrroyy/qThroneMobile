package app.rawline.mobile.ktx

import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.SettingsRegistry
import moe.matsuri.nb4a.utils.CoreLog
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // Gated at the source by log_level; an unreadable DataStore (JVM tests) lets the line through.
    private fun enabled(required: String): Boolean {
        return runCatching { SettingsRegistry.logAllows(DataStore.logLevel, required) }.getOrDefault(true)
    }

    // No app context in JVM unit tests: a failed write is ignored.
    private fun printLog(line: String) {
        runCatching { CoreLog.write(line) }
    }

    fun d(message: String) {
        if (!enabled("debug")) return
        printLog("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        if (!enabled("debug")) return
        printLog("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        if (!enabled("info")) return
        printLog("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        if (!enabled("info")) return
        printLog("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        if (!enabled("warn")) return
        printLog("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        if (!enabled("warn")) return
        printLog("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        if (!enabled("warn")) return
        printLog("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        if (!enabled("error")) return
        printLog("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        if (!enabled("error")) return
        printLog("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        if (!enabled("error")) return
        printLog("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}