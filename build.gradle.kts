// ShopServer —— 单模块 Ktor 后端项目
// 与 Android App（Shop）使用相同的数据契约和 JSON 格式
plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "org.lzy.shop"
version = "0.1.0"

application {
    mainClass = "org.lzy.shop.server.ApplicationKt"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // ====== Ktor Server ======
    val ktorVersion = "3.5.2"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")

    // Content Negotiation (JSON)
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    // 认证：JWT
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")
    implementation("com.auth0:java-jwt:4.4.0")                       // JWT 生成/解析（JwtService 用）

    // 常用插件
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-compression-jvm:$ktorVersion")

    // ====== 数据库：Exposed + H2 ======
    // JetBrains Exposed 1.5.x —— 官方推荐最新稳定版
    // 迁移指南：旧 DSL select { } → 新 DSL selectAll().where { }
    val exposedVersion = "1.5.1"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // H2 嵌入式数据库（零配置，数据存本地文件）
    runtimeOnly("com.h2database:h2:2.3.232")
    // PostgreSQL 驱动 —— 当 DB_URL 设为 jdbc:postgresql://... 时自动使用
    runtimeOnly("org.postgresql:postgresql:42.7.4")

    // ====== 工具库 ======
    implementation("io.insert-koin:koin-core:4.0.0")
    implementation("io.insert-koin:koin-ktor:4.0.0")
    implementation("cn.hutool:hutool-all:5.8.34")           // 常用工具（加密、验证码等）

    // ====== 日志 ======
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // ====== 测试 ======
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

// Kotlin 编译选项
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

tasks.test {
    useJUnitPlatform()
}

// fat jar 打包
tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.lzy.shop.server.ApplicationKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
