package org.lzy.shop.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.resource
import io.ktor.server.http.content.resources
import io.ktor.server.http.content.static
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.lzy.shop.server.auth.configureJwt
import org.lzy.shop.server.common.BaseResponse
import org.lzy.shop.server.common.ResultCode
import org.lzy.shop.server.config.AppConfig
import org.lzy.shop.server.config.DatabaseConfig
import org.lzy.shop.server.route.*

/**
 * Ktor 后端启动入口
 *
 * 启动流程：
 * 1. 连接数据库（H2 嵌入式，首次启动自动建表）
 * 2. 安装 Ktor 插件（JSON 序列化、CORS、JWT 认证、日志等）
 * 3. 挂载所有路由模块（App API + Admin API + 静态页面）
 * 4. 启动 Netty HTTP 服务器
 *
 * 运行方式：
 *   ./gradlew run             （开发模式，热重载）
 *   java -jar ShopServer.jar （打包后，静态文件从 classpath 加载）
 *
 * 环境变量覆盖（生产部署）：
 *   SERVER_PORT=80 SERVER_HOST=0.0.0.0
 *   DB_URL=jdbc:mysql://host:3306/shop DB_USER=root DB_PASSWORD=xxx
 *   ADMIN_USER=admin ADMIN_PASSWORD=xxx
 *   APP_BASE_URL=https://shop.yourdomain.com
 *
 * 域名部署（Nginx 反向代理）：
 *   server {
 *       listen 80;
 *       server_name shop.yourdomain.com;
 *       location / {
 *           proxy_pass http://127.0.0.1:8080;
 *           proxy_set_header Host $host;
 *           proxy_set_header X-Real-IP $remote_addr;
 *       }
 *   }
 */
fun Application.module() {
    // 1. 数据库
    DatabaseConfig.connect()

    // 2. 默认响应头
    install(DefaultHeaders) {
        header("X-Powered-By", "Ktor-ShopServer")
    }

    // 3. JSON 序列化 —— 与 Android App 端配置完全对齐
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = false
            isLenient = true
            explicitNulls = false
            encodeDefaults = true
            coerceInputValues = true
            allowSpecialFloatingPointValues = true
        })
    }

    // 4. CORS（开发用允许所有来源，生产环境收紧）
    install(CORS) {
        anyHost()
        allowHeader("Authorization")
        allowHeader("Content-Type")
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Put)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }

    // 5. 压缩
    install(Compression) {
        gzip()
        deflate()
    }

    // 6. 请求日志
    install(CallLogging) {
        format { call ->
            val status = call.response.status()?.value
            val method = call.request.httpMethod.value
            val path = call.request.path()
            "$method $path -> $status"
        }
    }

    // 7. 异常 & 错误页 —— 统一返回 BaseResponse
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.OK,
                BaseResponse<Any>(
                    code = ResultCode.INTERNAL_ERROR,
                    message = "服务器内部错误: ${cause.message}"
                )
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.OK,
                BaseResponse<Any>(
                    code = ResultCode.NOT_FOUND,
                    message = "接口不存在: ${call.request.path()}"
                )
            )
        }
    }

    // 8. JWT 认证（App 端用户用）
    configureJwt()

    // 9. 路由挂载
    routing {
        // ===== 健康检查 =====
        get("/health") {
            call.respond(BaseResponse(data = "ok"))
        }

        // ====== App API（Android/iOS 端） ======
        route("app") {
            configureUserRoutes()
            configureHomeRoutes()
            configureGoodsRoutes()
            configureOrderRoutes()
            configureAddressRoutes()
            configureCouponRoutes()
            configureCollectionRoutes()
            configureNotificationRoutes()
            configureFeedbackRoutes()
            configureCustomerServiceRoutes()
            configureUploadRoutes()
        }

        // ====== Admin API（后台管理） ======
        route("admin") {
            configureAdminRoutes()
        }

        // ====== Admin 前端页面 ======
        // 从 classpath 的 "admin" 目录加载静态资源（开发和 jar 部署都能用）
        // 优先级：Route 里精确匹配的 API handler > 这里的静态资源
        static("admin") {
            resources("admin")
            // SPA 回退：找不到文件时返回 index.html（方便前端路由）
            resource("index.html", "admin/index.html")
            get {
                resource("admin/index.html")
            }
        }
    }
}

fun main() {
    val baseUrl = System.getenv("APP_BASE_URL") ?: "http://${AppConfig.HOST}:${AppConfig.PORT}"

    println()
    println("╔══════════════════════════════════════════╗")
    println("║        ShopServer 启动成功                ║")
    println("╠══════════════════════════════════════════╣")
    println("║  API 基址 : $baseUrl                     ║")
    println("║  健康检查 : $baseUrl/health               ║")
    println("║  App API  : $baseUrl/app/*               ║")
    println("║  Admin API: $baseUrl/admin/*              ║")
    println("║  管理后台 : $baseUrl/admin                ║")
    println("╠══════════════════════════════════════════╣")
    println("║  HOST     : ${AppConfig.HOST}            ║")
    println("║  PORT     : ${AppConfig.PORT}            ║")
    println("║  DB       : ${AppConfig.Db.URL}          ║")
    println("║  ADMIN    : ${AppConfig.Admin.USERNAME}  ║")
    println("╚══════════════════════════════════════════╝")
    println()

    embeddedServer(
        factory = Netty,
        port = AppConfig.PORT,
        host = AppConfig.HOST,
        module = Application::module
    ).start(wait = true)
}
