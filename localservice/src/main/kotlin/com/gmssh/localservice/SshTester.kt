package com.gmssh.localservice

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource

import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.core.CoreModuleProperties
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 真 SSH 连接测试：建连 → 认证 → 开会话 → 断开。
 *
 * 只验证凭据可用性，不执行任何命令，不修改 sshd 配置。
 *
 * 实现：Apache MINA SSHD 2.13.x（纯 Java，Android 可用）。
 * 私钥解析走 MINA 自带的 OpenSSH 新格式解析器（依赖 BouncyCastle），
 * 因此 `-----BEGIN OPENSSH PRIVATE KEY-----` 的 ed25519 / RSA / ECDSA 都能认。
 */
object SshTester {

    private const val TAG = "GmSsh"
    private val CONNECT_TIMEOUT: Duration = Duration.ofMillis(12_000)
    private val AUTH_TIMEOUT: Duration = Duration.ofMillis(15_000)

    /** 注册 BouncyCastle：MINA 解析 OpenSSH 新格式私钥 / EdDSA 时需要 */
    private val bcReady: Boolean = runCatching {
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
        Log.i(TAG, "bouncycastle ready, eddsaSupported=${SecurityUtils.isEDDSACurveSupported()}")
        true
    }.getOrElse {
        Log.w(TAG, "bouncycastle init failed: ${it.message}")
        false
    }

    data class Result(
        val ok: Boolean,
        /** 给用户看的文案（失败时前端会原样 toast） */
        val message: String,
        /** 排障细节，只进日志 / 返回体 detail */
        val detail: String? = null,
    )

    /**
     * @param host      IP / 域名
     * @param port      端口
     * @param user      用户名
     * @param authType  "pass"（密码）或 "ca"（私钥）；其余按私钥处理
     * @param authValue 密码明文 或 私钥全文
     * @param keyPass   私钥口令（可空）
     */
    fun test(
        host: String,
        port: Int,
        user: String,
        authType: String,
        authValue: String,
        keyPass: String?,
    ): Result {
        if (host.isBlank()) return Result(false, "Host 地址不能为空")
        if (user.isBlank()) return Result(false, "用户名不能为空")
        if (authValue.isBlank()) {
            return Result(false, if (isKey(authType)) "私钥内容不能为空" else "密码不能为空")
        }

        val started = System.currentTimeMillis()
        var client: SshClient? = null
        var session: ClientSession? = null
        try {
            client = SshClient.setUpDefaultClient()
            // 与外部服务一致：不校验 known_hosts（App 内没有可信主机库）
            client.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client, CONNECT_TIMEOUT)
            CoreModuleProperties.AUTH_TIMEOUT.set(client, AUTH_TIMEOUT)
            client.start()

            Log.i(TAG, "connecting $user@$host:$port authType=$authType")
            session = client.connect(user, host, port).verify(CONNECT_TIMEOUT).session
            Log.i(
                TAG,
                "handshake ok in ${System.currentTimeMillis() - started}ms, " +
                    "server=${runCatching { session.serverKey?.algorithm }.getOrNull()}"
            )

            if (isKey(authType)) {
                val identities = loadKeyPairs(authValue, keyPass)
                identities.forEach { session.addPublicKeyIdentity(it) }
                Log.i(TAG, "loaded ${identities.size} private key identit(y/ies)")
            } else {
                session.addPasswordIdentity(authValue)
            }

            // 认证：不抛异常即成功
            session.auth().verify(AUTH_TIMEOUT)
            if (!session.isAuthenticated) {
                return Result(false, "认证失败：服务端未接受该凭据", "isAuthenticated=false")
            }

            // 真正开一个 session 通道，确认不只是过了认证（失败不影响结论，仅记录）
            runCatching {
                val ch = session.createChannel("session")
                ch.open().verify(5, TimeUnit.SECONDS)
                ch.close(false)
            }.onFailure { Log.w(TAG, "channel open (non-fatal): ${it.message}") }

            val ms = System.currentTimeMillis() - started
            Log.i(TAG, "SSH test OK $user@$host:$port in ${ms}ms")
            return Result(
                true,
                "连接成功",
                "authenticated in ${ms}ms, method=${if (isKey(authType)) "publickey" else "password"}, engine=mina-sshd"
            )
        } catch (t: Throwable) {
            val detail = describe(t)
            Log.w(TAG, "SSH test FAILED $user@$host:$port -> $detail")
            return Result(false, friendly(t), detail)
        } finally {
            runCatching { session?.close(false) }
            runCatching { client?.stop() }
        }
    }

    private fun isKey(authType: String): Boolean {
        val a = authType.trim().lowercase()
        return a == "ca" || a == "key" || a == "publickey" || a == "pubkey" || a == "privatekey"
    }

    /**
     * 用「临时文件 + 立即解析」加载私钥。
     *
     * MINA 的 FileKeyPairProvider 是惰性解析的，文件必须在认证期间一直存在；
     * 这里改用 SecurityUtils.loadKeyPairIdentities(..., InputStream, ...) 立即物化成
     * KeyPair 列表，解析完就能删掉临时文件（私钥不留盘）。
     *
     * 解析器是 SecurityUtils.getKeyPairResourceParser()，MINA 默认已聚合：
     * OpenSSH 新格式（-----BEGIN OPENSSH PRIVATE KEY-----：ed25519/ecdsa/rsa/dss）、
     * PKCS#8、PKCS#1、PuTTY .ppk。
     */
    private fun loadKeyPairs(keyText: String, passphrase: String?): List<KeyPair> {
        val text = keyText.trim()
        if (text.isEmpty()) throw IllegalArgumentException("私钥内容为空")

        val tmp: Path = Files.createTempFile("gmkey", ".pem")
        try {
            Files.write(tmp, text.toByteArray(Charsets.UTF_8))
            restrictPermissions(tmp)

            val provider = passwordProvider(passphrase)
            val pairs = Files.newInputStream(tmp).use { ins ->
                SecurityUtils.loadKeyPairIdentities(
                    null as SessionContext?,
                    null as NamedResource?,
                    ins,
                    provider,
                ).toList()
            }
            if (pairs.isEmpty()) {
                throw IllegalArgumentException("无法解析私钥（支持 OpenSSH / PKCS#8 / PKCS#1 / PPK）")
            }
            Log.i(TAG, "key loaded: ${pairs.joinToString(",") { it.public.algorithm }}")
            return pairs
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (t: Throwable) {
            throw IllegalArgumentException(
                "无法解析私钥（支持 OpenSSH / PKCS#8 / PKCS#1 / PPK）。详情：${t.javaClass.simpleName}: ${t.message}"
            )
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** 私钥临时文件收紧到 600（非 POSIX 文件系统忽略） */
    private fun restrictPermissions(p: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                p,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }.onFailure { runCatching { p.toFile().setReadable(false, false); p.toFile().setReadable(true, true) } }
    }

    /** 固定口令；解不开直接 IGNORE（不重试、不弹交互） */
    private fun passwordProvider(passphrase: String?): FilePasswordProvider {
        val pass = passphrase?.takeIf { it.isNotEmpty() } ?: return FilePasswordProvider.EMPTY
        return object : FilePasswordProvider {
            override fun getPassword(session: SessionContext?, resource: NamedResource?, retryIndex: Int): String {
                if (retryIndex > 0) throw IOException("私钥口令不正确")
                return pass
            }

            override fun handleDecodeAttemptResult(
                session: SessionContext?,
                resource: NamedResource?,
                retryIndex: Int,
                password: String?,
                error: Exception?,
            ): FilePasswordProvider.ResourceDecodeResult =
                if (error == null) FilePasswordProvider.ResourceDecodeResult.TERMINATE
                else FilePasswordProvider.ResourceDecodeResult.IGNORE
        }
    }

    private fun friendly(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.toList()
        val all = chain.joinToString(" ") { it.message ?: "" }.lowercase()
        return when {
            all.contains("无法解析私钥") -> t.message ?: "无法解析私钥"
            all.contains("exhausted available authentication methods") ||
                all.contains("no more authentication methods") ||
                all.contains("auth fail") || all.contains("auth cancel") ||
                all.contains("unable to authenticate") ->
                "认证失败：用户名/密码或私钥不正确（服务端拒绝了该凭据）"
            all.contains("connection refused") || all.contains("econnrefused") ->
                "连接被拒绝：目标端口没有 SSH 服务在监听"
            all.contains("timeout") || all.contains("timed out") ->
                "连接超时：请检查 Host / 端口 与网络连通性"
            all.contains("unknown host") || all.contains("no such host") || all.contains("unresolved") ->
                "无法解析主机名：请检查 Host 地址"
            all.contains("no route to host") || all.contains("network is unreachable") ->
                "网络不可达：请检查网络与防火墙"
            all.contains("kex") || all.contains("algorithm") || all.contains("negotiat") ->
                "协议协商失败：客户端与服务端没有共同的算法"
            all.contains("key") && (all.contains("format") || all.contains("decode") || all.contains("parse")) ->
                "私钥格式不支持：请提供 OpenSSH / PKCS#8 / PKCS#1 / PPK 格式的私钥"
            else -> "连接失败：" + (chain.lastOrNull()?.message ?: t.javaClass.simpleName)
        }
    }

    private fun describe(t: Throwable): String =
        generateSequence(t) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
}
