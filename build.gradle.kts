// ShopServer —— 单模块 Ktor 后端项目
// 与 Android App（Shop）使用相同的数据契约和 JSON 格式
plugins {
    // 与 Ktor 3.5.2 自身编译用的 kotlin-stdlib 2.3.21 完全对齐
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

// ====== 版本常量 —— 完全对齐 Ktor 3.5.2 的 POM 依赖 ======
val ktorVersion = "3.5.2"
val kotlinVersion = "2.3.21"
val serializationVersion = "1.11.0"
val coroutinesVersion = "1.11.0"

dependencies {
    // ====== kotlinx-serialization runtime —— 显式声明防止传递依赖歧义 ======
    // Ktor 的 ktor-serialization-kotlinx-json-jvm 也会传这两个（同样版本 1.11.0、同样 -jvm 后缀）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:$serializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:$serializationVersion")

    // ====== Ktor Server ======
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
    val exposedVersion = "1.5.1"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // H2 嵌入式数据库（零配置，数据存本地文件）
    runtimeOnly("com.h2database:h2:2.3.232")

    // ====== 工具库 ======
    implementation("io.insert-koin:koin-core:4.0.0")
    implementation("io.insert-koin:koin-ktor:4.0.0")
    implementation("cn.hutool:hutool-all:5.8.34")

    // ====== 日志 ======
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // ====== 测试 ======
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

// ====== 强制版本锁定 —— Gradle 不允许任何依赖解析出不同版本 ======
// 确保 classpath 上只有一份 kotlinx-serialization、kotlin-stdlib、kotlinx-coroutines
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion")
        force("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
        force("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:$serializationVersion")
        force("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
        force("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:$serializationVersion")
        force("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$coroutinesVersion")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    }
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
