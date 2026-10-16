// ShopServer —— 单模块 Ktor 后端项目
// 与 Android App（Shop）使用相同的数据契约和 JSON 格式
plugins {
    // Kotlin 版本与 Ktor 3.5.2 官方文档一致（2.3.0），避免编译器版本过新导致序列化插件行为异常
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
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
    // ====== 显式锁定 kotlinx-serialization runtime 版本 ======
    // 避免 Ktor 传递依赖的版本和编译器插件生成的代码不兼容
    // 2026-10 最新正式版为 1.11.0
    val serializationRuntimeVersion = "1.11.0"
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:$serializationRuntimeVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:$serializationRuntimeVersion")

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

// 使用 Gradle 官方 installDist 生成标准分发包（每个依赖独立 jar，无打包问题）
