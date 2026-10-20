package org.lzy.shop.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.lzy.shop.server.common.ResultCode
import org.lzy.shop.server.common.fail
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
 * Admin 鉴权拦截器 —— 为 admin 路由创建一个 routeScopedPlugin
 *
 * 逻辑：
 * 1. login 接口放行（不拦截）
 * 2. 其他所有请求检查 Authorization header，验证 admin token
 * 3. 失败返回 401
 */
private val AdminAuthPlugin = createRouteScopedPlugin("AdminAuthPlugin") {
    onCall { appCall ->
        val fullPath = appCall.request.path()
        val path = fullPath.removePrefix("/admin/").removePrefix("/admin")
        if (path == "login") return@onCall

        val header = appCall.request.header("Authorization")?.trim()
        val token = header?.removePrefix("Bearer ")?.trim()
        val username = if (!token.isNullOrBlank()) AdminAuth.verifyToken(token) else null

        if (username == null) {
            appCall.respond(
                HttpStatusCode.Unauthorized,
                fail<String>(code = ResultCode.UNAUTHORIZED, message = "管理员未登录或token已过期")
            )
        }
    }
}

fun Route.installAdminAuth() {
    install(AdminAuthPlugin)
}
