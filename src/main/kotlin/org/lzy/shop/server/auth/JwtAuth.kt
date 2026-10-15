package org.lzy.shop.server.auth

import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.respond
import org.lzy.shop.server.common.ResultCode
import org.lzy.shop.server.config.AppConfig

/**
 * JWT 认证配置 —— 与 App 端 TokenInterceptor 完全对齐
 *
 * App 端行为：
 *   - 每次请求在 Authorization header 里带 token
 *   - App 端**不传 "Bearer " 前缀**，直接传 token 值
 *   - Token 过期时 App 收到 HTTP 401，会自动用 refreshToken 换一个新的
 *
 * 服务端行为：
 *   - 自定义 authHeader：直接从 Authorization header 取 token 值
 *   - 校验签名 + 过期时间
 *   - 校验失败返回 401，让 App 端触发刷新流程
 */
fun Application.configureJwt() {
    val jwtConfig = AppConfig.Jwt

    authentication {
        jwt("auth-jwt") {
            // 自定义 authHeader：从 Authorization header 直接取 token（无 Bearer 前缀）
            // Ktor 3.x 需要返回 HttpAuthHeader?，用 Single 包装原始 token 值
            authHeader { call ->
                call.request.headers[jwtConfig.HEADER_NAME]?.trim()
                    ?.let { HttpAuthHeader.Single(it,"") }
            }

            verifier(
                com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtConfig.SECRET)).build()
            )

            validate { credential ->
                val userId = credential.payload.getClaim("userId")?.asLong()
                val phone = credential.payload.getClaim("phone")?.asString()
                if (userId != null && phone != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }

            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    org.lzy.shop.server.common.fail<Any>(
                        code = ResultCode.UNAUTHORIZED,
                        message = "登录已过期，请重新登录"
                    )
                )
            }
        }
    }
}

/**
 * 从当前 call 中安全取出已认证用户的信息
 * 在需要登录的路由里调用
 */
fun ApplicationCall.currentUserId(): Long {
    val principal = principal<JWTPrincipal>()
        ?: throw IllegalStateException("未登录路由却调用了 currentUserId()")
    return principal.payload.getClaim("userId").asLong()
}

/** 安全取用户 ID，未登录返回 null */
fun ApplicationCall.currentUserIdOrNull(): Long? {
    return principal<JWTPrincipal>()
        ?.payload?.getClaim("userId")?.asLong()
}

fun ApplicationCall.currentUserPhone(): String {
    val principal = principal<JWTPrincipal>()
        ?: throw IllegalStateException("未登录路由却调用了 currentUserPhone()")
    return principal.payload.getClaim("phone").asString()
}
