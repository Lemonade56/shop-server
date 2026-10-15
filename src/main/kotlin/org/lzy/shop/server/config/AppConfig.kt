package org.lzy.shop.server.config

/**
 * 应用配置 —— 所有可调参数集中在这里
 *
 * 优先级：环境变量 > 系统属性 > 默认值
 * 生产部署时可以通过环境变量覆盖：
 *   SERVER_PORT=80 SERVER_HOST=shop.example.com
 *   DB_URL=jdbc:mysql://localhost:3306/shop DB_USER=root DB_PASSWORD=xxx
 */
object AppConfig {

    /** 监听端口 —— 优先读 PORT（云平台惯例），其次 SERVER_PORT，默认 8080 */
    val PORT: Int = System.getenv("PORT")?.toIntOrNull()
        ?: System.getenv("SERVER_PORT")?.toIntOrNull()
        ?: System.getProperty("server.port")?.toIntOrNull()
        ?: 8080

    /** 监听地址 —— 环境变量 SERVER_HOST 可覆盖，域名部署时设为对应网卡或 0.0.0.0 */
    val HOST: String = System.getenv("SERVER_HOST")
        ?: System.getProperty("server.host")
        ?: "0.0.0.0"

    // ===== JWT =====
    object Jwt {
        /** 签名密钥（生产环境必须换成随机长字符串并放入环境变量 JWT_SECRET） */
        val SECRET: String = System.getenv("JWT_SECRET")
            ?: "ShopServer-Super-Secret-Key-Please-Change-Me-2026"

        /** Token 有效期（毫秒）：30 天 */
        const val ACCESS_TOKEN_TTL = 30L * 24 * 60 * 60 * 1000

        /** RefreshToken 有效期（毫秒）：60 天 */
        const val REFRESH_TOKEN_TTL = 60L * 24 * 60 * 60 * 1000

        /** Token 请求头名称 —— 与 App 端 TokenInterceptor 对齐 */
        const val HEADER_NAME = "Authorization"
    }

    // ===== 数据库 =====
    object Db {
        /** JDBC URL —— 环境变量 DB_URL 可覆盖（MySQL 示例: jdbc:mysql://localhost:3306/shop） */
        val URL: String = System.getenv("DB_URL")
            ?: "jdbc:h2:file:./data/shop;DB_CLOSE_DELAY=-1;MODE=MySQL"

        /** 数据库用户名 —— 环境变量 DB_USER 可覆盖 */
        val USER: String = System.getenv("DB_USER") ?: "sa"

        /** 数据库密码 —— 环境变量 DB_PASSWORD 可覆盖 */
        val PASSWORD: String = System.getenv("DB_PASSWORD") ?: ""
    }

    // ===== 后台管理 =====
    object Admin {
        /** 管理员用户名 —— 环境变量 ADMIN_USER 可覆盖 */
        val USERNAME: String = System.getenv("ADMIN_USER") ?: "admin"

        /** 管理员密码 —— 环境变量 ADMIN_PASSWORD 可覆盖 */
        val PASSWORD: String = System.getenv("ADMIN_PASSWORD") ?: "admin123"
    }
}
