package com.gmssh.localservice

import java.io.File

/**
 * JVM 入口 —— 用于在服务器上跑真服务 + Playwright 端到端验证。
 *
 *   ./gradlew :localservice:run --args="--port 8898 --data /tmp/gmssh-data"
 *
 * 启动成功后打印一行机器可读的握手串：
 *   GMSSH_LOCAL_READY {"gmPort":8898,"multiPort":8898,"browserPort":8899,"gmServerStatus":true}
 */
fun main(args: Array<String>) {
    // ★ 必须在任何 MINA/SSHD 类被加载之前注册 BouncyCastle：
    //   MINA 的 SecurityUtils.EDDSA_SUPPORTED 是 static final，类一加载就定死；
    //   晚注册会让 ed25519 永远显示 unsupported。
    runCatching {
        runCatching {
            if (java.security.Security.getProvider("EdDSA") == null) {
                java.security.Security.addProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider())
            }
        }
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
        println("[boot] BouncyCastle registered, eddsaSupported=" +
            org.apache.sshd.common.util.security.SecurityUtils.isEDDSACurveSupported())
    }.onFailure { println("[boot] BC register failed: " + it.message) }

    var port = 0
    var bPort = 0
    var host = LocalServiceServer.DEFAULT_HOST
    var data = File(System.getProperty("user.home"), ".gmssh")
    var www: File? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: port
            "--browser-port" -> bPort = args.getOrNull(++i)?.toIntOrNull() ?: bPort
            "--host" -> host = args.getOrNull(++i) ?: host
            "--data", "--data-dir" -> data = File(args.getOrNull(++i) ?: data.path)
            "--www", "--www-dir" -> www = args.getOrNull(++i)?.let { File(it) }
            "--quiet" -> Log.verbose = false
            "-h", "--help" -> {
                println("usage: [--port N] [--browser-port N] [--host 127.0.0.1] [--data DIR] [--www DIR] [--quiet]")
                return
            }
            else -> Log.w("GmMain", "unknown arg: ${args[i]}")
        }
        i++
    }

    if (www != null && !File(www!!.path, "index.html").isFile) {
        println("[boot] WARN --www ${www!!.absolutePath} 下没有 index.html，静态托管可能 404")
    }

    println(
        "GMSSH local service starting… data=${data.absolutePath} " +
            "port=${if (port == 0) "auto" else port} www=${www?.absolutePath ?: "(disabled)"}"
    )
    val ok = LocalService.start(data, port, bPort, host, www)
    if (!ok) {
        println("GMSSH_LOCAL_FAILED ${LocalService.lastError}")
        kotlin.system.exitProcess(1)
    }

    println("GMSSH_LOCAL_READY ${LocalService.portJson()}")

    // 阻塞直到进程被杀
    Runtime.getRuntime().addShutdownHook(Thread {
        LocalService.stop()
    })
    Thread.currentThread().join()
}
