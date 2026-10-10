package io.github.v2compose.singbox

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 内嵌核心的分步日志。
 *
 * native 崩溃时 logcat 可能被冲掉，所以同时按行追加到 App 的外部私有目录：
 * /sdcard/Android/data/<包名>/files/singbox-steps.log
 * 该文件可以用 adb pull 直接取出，release / debug 均可。
 */
internal object SingBoxStepLogger {

    private const val TAG = "SingBoxCore"

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            dir.mkdirs()
            logFile = File(dir, "singbox-steps.log")
        }
    }

    fun step(message: String) {
        Log.i(TAG, message)
        runCatching { logFile?.appendText(message + "\n") }
    }

    fun reset() {
        runCatching { logFile?.writeText("") }
    }
}
