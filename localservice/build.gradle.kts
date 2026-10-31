plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // HTTP 服务：NanoHTTPD，纯 Java，Android 友好
    api("org.nanohttpd:nanohttpd:2.3.1")
    // SSH 客户端：Apache MINA SSHD（纯 Java，Android 可用）
    api("org.apache.sshd:sshd-core:2.13.2")
    // 为后续 P3 文件管理（SFTP）预留
    api("org.apache.sshd:sshd-sftp:2.13.2")
    // JSON：Gson（纯 Java，Android/桌面通用，避免 org.json 双份类冲突）
    api("com.google.code.gson:gson:2.10.1")
    // MINA 解析 OpenSSH 新格式（ed25519/ecdsa）私钥依赖 BouncyCastle
    // MINA SSHD 的 ed25519 依赖 i2p EdDSA 提供者（不是 BC 自带）
    api("net.i2p.crypto:eddsa:0.3.0")
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    api("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // MINA 用 slf4j，给个简单实现免得刷 warning
    runtimeOnly("org.slf4j:slf4j-simple:2.0.7")
}

application {
    mainClass.set("com.gmssh.localservice.MainKt")
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
