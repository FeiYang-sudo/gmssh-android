package com.gmssh.localservice

import java.io.File

/**
 * 本地服务生命周期控制器 —— Android 与 JVM 共用。
 *
 * Android 侧：`LocalService.start(File(filesDir, "gmssh"))`，然后把 [portJson] 交给 `get-port`。
 * JVM 侧：`Main.kt` 的 `main()` 调它。
 */
object LocalService {

    private const val TAG = "GmSvc"

    @Volatile
    var gmPort: Int = 0
        private set

    @Volatile
    var browserPort: Int = 0
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var main: LocalServiceServer? = null
    private var browser: LocalServiceServer? = null
    private var store: Store? = null
    private var baseDir: File? = null

    /**
     * 幂等启动。已在运行则直接返回 true。
     *
     * @param dataDir  数据目录（Android: filesDir/gmssh；JVM: 任意）
     * @param port     期望端口，0 = 由系统分配（推荐，避免端口被占）
     * @param bPort    期望 browserPort，0 = 自动（gmPort+1，失败则复用 gmPort）
     */
    @Synchronized
    fun start(dataDir: File, port: Int = 0, bPort: Int = 0, host: String = LocalServiceServer.DEFAULT_HOST): Boolean {
        if (running) return true
        lastError = null
        return try {
            val st = store ?: Store(dataDir).also { store = it }
            baseDir = dataDir

            val srv = LocalServiceServer(host, port, st)
            srv.start(NanoHTTPD_START_TIMEOUT, true)
            main = srv
            gmPort = srv.listeningPort
            running = true
            Log.i(TAG, "local service listening on http://$host:$gmPort (data=${dataDir.absolutePath})")

            // browserPort：优先用期望值，其次 gmPort+1，再不行复用 gmPort
            var bp = 0
            val candidates = buildList {
                if (bPort > 0) add(bPort)
                add(gmPort + 1)
            }
            for (c in candidates) {
                if (c == gmPort) continue
                try {
                    val b = LocalServiceServer(host, c, st)
                    b.start(NanoHTTPD_START_TIMEOUT, true)
                    browser = b
                    bp = b.listeningPort
                    Log.i(TAG, "browser service listening on http://$host:$bp")
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "browser port $c unavailable: ${t.message}")
                }
            }
            browserPort = if (bp > 0) bp else gmPort
            true
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "start failed: $lastError")
            running = false
            false
        }
    }

    /** 停止（Android onDestroy / 测试收尾） */
    @Synchronized
    fun stop() {
        runCatching { main?.stop() }
        runCatching { browser?.stop() }
        main = null
        browser = null
        running = false
        gmPort = 0
        browserPort = 0
        Log.i(TAG, "local service stopped")
    }

    /**
     * 给 `get-port` IPC 用：必须是前端期望的裸对象
     * `{gmPort, multiPort, browserPort, gmServerStatus}`。
     * multiPort 与 gmPort 同实例（业务 API 走 multiPort）。
     */
    fun portJson(): String {
        val g = if (running) gmPort else 0
        val b = if (running && browserPort > 0) browserPort else g
        return """{"gmPort":$g,"multiPort":$g,"browserPort":$b,"gmServerStatus":${running},"error":${if (lastError == null) "null" else "\"" + lastError!!.replace("\"", "'") + "\""}}"""
    }

    private const val NanoHTTPD_START_TIMEOUT = 8000
}
