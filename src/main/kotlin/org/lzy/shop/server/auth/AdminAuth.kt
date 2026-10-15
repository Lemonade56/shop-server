package org.lzy.shop.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import org.lzy.shop.server.config.AppConfig
import java.util.Date

/**
 * 后台管理员 JWT —— 使用与 App 用户相同的 secret，但加上 role=admin 标记
 */
object AdminAuth {

    private val algorithm = Algorithm.HMAC256(AppConfig.Jwt.SECRET)
    private val verifier = JWT.require(algorithm).build()

    private const val TOKEN_TTL = 24L * 60 * 60 * 1000  // 24 小时

    /** 生成管理员登录 token */
    fun issueToken(username: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withClaim("role", "admin")
            .withClaim("username", username)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + TOKEN_TTL))
            .sign(algorithm)
    }

    /** 验证 token，返回 username；无效返回 null */
    fun verifyToken(token: String): String? {
        return try {
            val decoded = verifier.verify(token)
            if (decoded.getClaim("role").asString() == "admin") {
                decoded.getClaim("username").asString()
            } else null
        } catch (e: JWTVerificationException) {
            null
        }
    }
}

/** 从 Authorization header 取 token 并验证，成功返回 username，失败返回 null */
fun ApplicationCall.adminUsername(): String? {
    val header = request.header("Authorization")
    val token = header?.trim()?.removePrefix("Bearer ")?.trim()
    return token?.let { AdminAuth.verifyToken(it) }
}

/**
 * Admin 鉴权拦截器 —— 遍历 AdminRoute 下所有非 login 的 handler 包裹检查
 *
 * Ktor 3.x 的 routeScopedPlugin API 较复杂，这里用手动拦截方式：
 * 在每个 handler 开头调用 call.adminUsername()，为空直接 return 未登录响应
 */
fun Route.installAdminAuth() {
    // 占位函数：实际鉴权在 AdminRoute 各 handler 里手动处理
    // 这样避免依赖 Ktor 内部的拦截器 API 变化
}
