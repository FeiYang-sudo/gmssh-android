package com.gmssh.localservice

import java.io.File
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android / JVM 通用启动入口 —— 「启动并返回真端口」。
 *
 * Android 侧（MainActivity / Application）：
 * ```
 * val port = LocalServiceLauncher.start(File(filesDir, "gmssh"))   // 后台线程调用
 * val json = LocalServiceLauncher.portJson()                       // get-port 直接返回它
 * ```
 *
 * 与 `Main.kt` 的 `main()` 共用 [LocalService] 这一套启动逻辑，区别只有：
 *   1. 这里**强制**先做 BouncyCastle / i2p EdDSA 提供者注册（见 [ensureCrypto]）；
 *   2. 这里返回端口，而不是阻塞进程。
 */
object LocalServiceLauncher {

    private const val TAG = "GmLauncher"

    @Volatile
    private var ready: CountDownLatch = CountDownLatch(1)

    @Volatile
    private var cryptoReady = false

    /** 已经注册过的提供者（幂等） */
    @Synchronized
    fun ensureCrypto() {
        if (cryptoReady) return
        cryptoReady = true
        runCatching {
            // ★ 必须在任何 org.apache.sshd.* 类被加载之前完成注册：
            //   SecurityUtils.EDDSA_SUPPORTED 是 static final，类一加载就定死。
            runCatching {
                if (Security.getProvider("EdDSA") == null) {
                    Security.addProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider())
                }
            }.onFailure { Log.w(TAG, "EdDSA provider register failed: ${it.message}") }

            // Android 自带的 "BC" 是 com.android.org.bouncycastle.*（阉割版），
            // 名字冲突会让 Security.addProvider 直接返回 -1，必须换掉。
            val existing = Security.getProvider("BC")
            val ours = org.bouncycastle.jce.provider.BouncyCastleProvider()
            if (existing == null || existing.javaClass.name != ours.javaClass.name) {
                runCatching { if (existing != null) Security.removeProvider("BC") }
                Security.insertProviderAt(ours, 1)
            }
        }.onFailure { Log.e(TAG, "crypto init failed: ${it.message}") }
    }

    /**
     * 启动本地服务。幂等：已在运行直接返回现有端口；正在启动则等待启动结果。
     *
     * @return 实际监听端口（失败返回 0，原因见 [LocalService.lastError]）
     */
    fun start(
        dataDir: File,
        host: String = LocalServiceServer.DEFAULT_HOST,
        port: Int = 0,
    ): Int {
        ensureCrypto()
        if (LocalService.running) return LocalService.gmPort

        synchronized(this) {
            if (LocalService.running) return LocalService.gmPort
            if (ready.count > 0) ready = CountDownLatch(1)
        }

        return try {
            dataDir.mkdirs()
            Log.i(TAG, "starting local service, data=${dataDir.absolutePath}")
            val ok = LocalService.start(dataDir, port, 0, host)
            if (ok) {
                Log.i(TAG, "local service ready on ${LocalService.gmPort}")
                LocalService.gmPort
            } else {
                Log.e(TAG, "local service failed: ${LocalService.lastError}")
                0
            }
        } catch (t: Throwable) {
            Log.e(TAG, "start threw: ${t.javaClass.simpleName}: ${t.message}")
            0
        } finally {
            ready.countDown()
        }
    }

    /** 后台线程启一次（Android 用；不阻塞调用线程） */
    fun startAsync(dataDir: File, host: String = LocalServiceServer.DEFAULT_HOST) {
        if (LocalService.running) return
        Thread({ start(dataDir, host) }, "gm-local-service").apply {
            isDaemon = true
            start()
        }
    }

    /** 等本地服务就绪（前端 get-port 时调用），超时返回 false */
    fun awaitReady(timeoutMs: Long): Boolean {
        if (LocalService.running) return true
        return runCatching { ready.await(timeoutMs, TimeUnit.MILLISECONDS) && LocalService.running }
            .getOrDefault(false)
    }

    /** get-port 的返回值：裸端口对象，前端已 JSON.parse 过一次 */
    fun portJson(): String = LocalService.portJson()
}
