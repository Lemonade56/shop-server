package org.lzy.shop.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import org.lzy.shop.server.config.AppConfig
import java.util.Date

/**
 * JWT 工具类 —— 生成 + 解析 Token
 *
 * 使用 auth0/java-jwt 库（纯 JWT 操作，不依赖 Ktor）
 */
object JwtService {

    private val algorithm = Algorithm.HMAC256(AppConfig.Jwt.SECRET)
    private val verifier = JWT.require(algorithm).build()

    /** 生成 AccessToken（30 天有效） */
    fun generateAccessToken(userId: Long, phone: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withClaim("userId", userId)
            .withClaim("phone", phone)
            .withClaim("type", "access")
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + AppConfig.Jwt.ACCESS_TOKEN_TTL))
            .sign(algorithm)
    }

    /** 生成 RefreshToken（60 天有效） */
    fun generateRefreshToken(userId: Long, phone: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withClaim("userId", userId)
            .withClaim("phone", phone)
            .withClaim("type", "refresh")
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + AppConfig.Jwt.REFRESH_TOKEN_TTL))
            .sign(algorithm)
    }

    /** 解析 Token，返回 Pair<userId, phone>；无效返回 null */
    fun parseToken(token: String): Pair<Long, String>? {
        return try {
            val decoded = verifier.verify(token)
            val userId = decoded.getClaim("userId").asLong()
            val phone = decoded.getClaim("phone").asString()
            userId to phone
        } catch (e: JWTVerificationException) {
            null
        }
    }
}
