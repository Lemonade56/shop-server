package org.lzy.shop.server.common

import org.lzy.shop.server.config.AppConfig

/**
 * 图片URL工具 —— 统一把数据库存的相对路径 /app/images/xxx.jpg
 * 拼上完整的服务器基础地址，返回给 App 端直接可用的完整 URL
 *
 * 基础地址来源：环境变量 APP_BASE_URL → 默认 http://HOST:PORT
 * 部署到 SnapDeploy 后需配置 APP_BASE_URL=https://你的域名
 */
object Img {

    /** 服务器基础URL，如 https://shop.example.com 或 http://localhost:8080 */
    val BASE: String = System.getenv("APP_BASE_URL")
        ?: "http://${AppConfig.HOST}:${AppConfig.PORT}"

    /**
     * 把图片相对路径转成完整 URL
     * 例：full("/app/images/banner-1.jpg") → "https://shop.example.com/app/images/banner-1.jpg"
     */
    fun full(path: String?): String? {
        if (path.isNullOrBlank()) return path
        // 已经是完整URL就不重复拼接
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        // BASE 末尾的 / 和 path 开头的 / 可能都有，统一处理
        val base = BASE.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return base + p
    }
}
