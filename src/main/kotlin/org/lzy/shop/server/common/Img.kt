package org.lzy.shop.server.common

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.request.uri

/**
 * 图片URL工具 —— 统一把数据库存的相对路径 /app/images/xxx.jpg
 * 拼上调用方实际访问的服务器地址，返回完整可用的 URL
 *
 * 动态取 host 而不是写死 APP_BASE_URL / 0.0.0.0，这样：
 *   - 本地开发访问 localhost:8080 → 返回 http://localhost:8080/app/images/xxx.jpg
 *   - SnapDeploy 域名访问 → 返回 https://你的域名/app/images/xxx.jpg
 *   - Nginx 反代（proxy_set_header Host 已传）→ 返回公网域名
 */
object Img {

    /** 静态兜底（无 call 上下文时用），优先读环境变量 APP_BASE_URL */
    private val STATIC_BASE: String = System.getenv("APP_BASE_URL")
        ?: "http://localhost:8080"

    /**
     * 从当前请求动态推断 base URL（推荐用这个）
     * 例：https://shop.example.com/admin → https://shop.example.com
     */
    fun baseFrom(call: ApplicationCall): String {
        val scheme = if (call.request.local.scheme == "https" ||
            call.request.headers["X-Forwarded-Proto"] == "https" ||
            call.request.headers["X-Forwarded-Scheme"] == "https"
        ) "https" else "http"

        // 优先用 X-Forwarded-Host（反代场景下的真实 host）
        val hostHeader = call.request.headers["X-Forwarded-Host"]
            ?: call.request.headers["Host"]
            ?: call.request.host()

        // Host 头可能带端口，也可能不带；端口优先取 X-Forwarded-Port
        val portHeader = call.request.headers["X-Forwarded-Port"]
            ?: call.request.headers["Host"]?.substringAfterLast(":", "")?.takeIf { it.isNotEmpty() }
        val localPort = portHeader?.toIntOrNull() ?: call.request.port()

        // 默认端口（http=80 / https=443）不拼到 URL 上
        val portSuffix = when {
            portHeader != null && localPort != 80 && localPort != 443 -> ":$localPort"
            localPort != 80 && localPort != 443 && hostHeader?.contains(":") != true -> ":$localPort"
            else -> ""
        }

        return "$scheme://${hostHeader?.substringBefore(":") ?: hostHeader}$portSuffix"
    }

    /**
     * 带 call 的完整 URL —— 推荐！所有新路由都用这个
     * 例：full(call, "/app/images/banner-1.jpg") → "https://shop.example.com/app/images/banner-1.jpg"
     */
    fun full(call: ApplicationCall, path: String?): String? {
        if (path.isNullOrBlank()) return path
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = baseFrom(call).trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return base + p
    }

    /**
     * 静态完整 URL —— 没有 call 上下文时的兜底，用环境变量 APP_BASE_URL
     */
    fun full(path: String?): String? {
        if (path.isNullOrBlank()) return path
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = STATIC_BASE.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return base + p
    }
}
