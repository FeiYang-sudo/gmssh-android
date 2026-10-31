package com.gmssh.localservice

/**
 * 极简日志门面。
 *
 * 本模块要同时跑在 JVM（服务器端验证）和 Android（APK 内）上，
 * 所以不依赖 android.util.Log —— Android 侧可以通过 [Log.sink] 把日志接到 logcat。
 */
object Log {

    /** Android 侧注入：Log.sink = { level, tag, msg -> android.util.Log.i(tag, msg) } */
    @Volatile
    var sink: ((String, String, String) -> Unit)? = null

    @Volatile
    var verbose: Boolean = true

    fun i(tag: String, msg: String) = emit("I", tag, msg)

    fun w(tag: String, msg: String) = emit("W", tag, msg)

    fun e(tag: String, msg: String) = emit("E", tag, msg)

    private fun emit(level: String, tag: String, msg: String) {
        sink?.let {
            runCatching { it(level, tag, msg) }
            return
        }
        if (!verbose && level == "I") return
        println("[$level/$tag] $msg")
    }
}
