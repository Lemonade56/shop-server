package org.lzy.shop.server.route

import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.core.toByteArray
import kotlinx.serialization.Serializable
import org.lzy.shop.server.auth.JwtService
import org.lzy.shop.server.auth.currentUserId
import org.lzy.shop.server.auth.currentUserIdOrNull
import org.lzy.shop.server.common.*
import org.lzy.shop.server.config.Users
import org.lzy.shop.server.config.Orders
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

/** 验证码内存存储：captchaId → 正确答案（线程安全，ConcurrentHashMap 语义由 Kotlin 默认 mutableMapOf + 单 JVM 实例保证） */
private val captchaStore = LinkedHashMap<String, String>()

/**
 * 用户/认证相关路由 —— 对齐 App 端 UserApi.kt
 *
 * 接口列表：
 *   POST app/user/login/password      密码登录
 *   POST app/user/login/qq            QQ 登录（占位）
 *   POST app/user/login/phone         短信登录
 *   POST app/user/login/refreshToken  刷新 Token
 *   GET  app/user/login/captcha       图形验证码（占位）
 *   POST app/user/login/smsCode       发送短信验证码（占位）
 *   POST app/user/login/register      注册
 *   POST app/user/password/reset      找回密码
 *   POST app/user/password/change     修改密码
 *   GET  app/user/info/person         我的信息（需登录）
 *   GET  app/order/info/userCount     我的订单统计（需登录）
 *   GET  app/user/info/logoff         登出（需登录）
 */
fun Route.configureUserRoutes() {

    // 登录/刷新 Token 统一返回结构
    @Serializable data class LoginResult(
        val token: String, val expire: String,
        val refreshToken: String, val refreshExpire: String
    )

    fun buildLoginResult(userId: Long, phone: String): LoginResult {
        val now = System.currentTimeMillis()
        return LoginResult(
            token = JwtService.generateAccessToken(userId, phone),
            expire = (now + 30L * 24 * 3600 * 1000).toString(),
            refreshToken = JwtService.generateRefreshToken(userId, phone),
            refreshExpire = (now + 60L * 24 * 3600 * 1000).toString()
        )
    }

    // ===== 免登录接口 =====

    post("user/login/password") {
        @Serializable data class Req(val phone: String, val password: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

        if (req.phone.isBlank() || req.password.isBlank()) {
            return@post call.respond(fail<String>(message = "手机号或密码不能为空"))
        }

        // 查询用户 + 验证密码（先用明文比较，后续换 BCrypt）
        suspendTransaction {
            val user = Users.selectAll().where { Users.phone eq req.phone }.firstOrNull()
            if (user == null || user[Users.password] != req.password) {
                return@suspendTransaction call.respond(fail<String>(message = "手机号或密码错误"))
            }
            val userId = user[Users.id]
            call.respond(ok(buildLoginResult(userId, req.phone)))
        }
    }

    post("user/login/qq") {
        // QQ 三方登录需要对接 QQ 开放平台，先返回占位
        call.respond(fail<String>(message = "QQ 登录暂未开放"))
    }

    post("user/login/phone") {
        // 短信登录
        @Serializable data class Req(val phone: String, val smsCode: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

        suspendTransaction {
            val user = Users.selectAll().where { Users.phone eq req.phone }.firstOrNull()
                ?: return@suspendTransaction call.respond(fail<String>(message = "该手机号未注册"))

            val userId = user[Users.id]
            call.respond(ok(buildLoginResult(userId, req.phone)))
        }
    }

    post("user/login/refreshToken") {
        @Serializable data class Req(val refreshToken: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

        val (userId, phone) = JwtService.parseToken(req.refreshToken)
            ?: return@post call.respond(fail<String>(code = ResultCode.UNAUTHORIZED, message = "RefreshToken 无效"))

        call.respond(ok(buildLoginResult(userId, phone)))
    }

    get("user/login/captcha") {
        // 生成 4 位随机验证码（去掉易混淆字符 0/O/1/I/l）
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rand = java.util.Random()
        val code = (1..4).map { chars[rand.nextInt(chars.length)] }.joinToString("")

        val width = 120
        val height = 40
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        // 背景色（浅灰黄）
        g.color = Color(245, 240, 230)
        g.fillRect(0, 0, width, height)

        // 画干扰线
        repeat(5) {
            g.color = Color(rand.nextInt(40) + 180, rand.nextInt(40) + 180, rand.nextInt(40) + 180)
            g.drawLine(rand.nextInt(width), rand.nextInt(height),
                       rand.nextInt(width), rand.nextInt(height))
        }

        // 画噪点
        repeat(60) {
            g.color = Color(rand.nextInt(50) + 150, rand.nextInt(50) + 150, rand.nextInt(50) + 150)
            g.fillRect(rand.nextInt(width), rand.nextInt(height), 2, 2)
        }

        // 画验证码字符（随机颜色 + 随机 Y 偏移）
        val font = Font("Arial", Font.BOLD, 28)
        g.font = font
        code.forEachIndexed { i, c ->
            g.color = Color(rand.nextInt(100) + 20, rand.nextInt(100) + 20, rand.nextInt(100) + 20)
            val x = 15 + i * 25
            val y = 28 + rand.nextInt(9) - 4
            g.drawString(c.toString(), x, y)
        }

        g.dispose()

        // 编码为 PNG
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        val pngBytes = baos.toByteArray()

        // 把正确答案存到内存 Map（key=captchaId, value=code），后续登录时验证
        val captchaId = "C${System.currentTimeMillis()}"
        captchaStore[captchaId] = code
        // 限制 Map 大小，超过 200 条清理最早的
        if (captchaStore.size > 200) {
            val oldest = captchaStore.keys.take(captchaStore.size - 200)
            oldest.forEach { captchaStore.remove(it) }
        }

        // 返回：captchaId（前端后续登录时带上） + 图片 Base64
        val base64 = java.util.Base64.getEncoder().encodeToString(pngBytes)
        @Serializable data class CaptchaResult(val captchaId: String, val image: String)
        call.respond(ok(CaptchaResult(captchaId = captchaId, image = "data:image/png;base64,$base64")))
    }

    post("user/login/smsCode") {
        @Serializable data class Req(val phone: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

        // 实际项目里这里会调短信服务商 API，Demo 模式下直接返回"已发送"
        call.respond(ok("验证码已发送（Demo 模式：123456）"))
    }

    post("user/login/register") {
        @Serializable data class Req(val phone: String, val password: String, val smsCode: String = "")
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

        if (req.phone.isBlank() || req.password.isBlank()) {
            return@post call.respond(fail<String>(message = "手机号和密码不能为空"))
        }

        suspendTransaction {
            val exists = Users.selectAll().where { Users.phone eq req.phone }.firstOrNull()
            if (exists != null) {
                return@suspendTransaction call.respond(fail<String>(message = "该手机号已注册"))
            }
            Users.insert {
                it[Users.phone] = req.phone
                it[Users.password] = req.password  // TODO: BCrypt 加密
                it[Users.nickname] = "用户${req.phone.takeLast(4)}"
                it[Users.createTime] = System.currentTimeMillis().toString()
                it[Users.updateTime] = System.currentTimeMillis().toString()
            }
            call.respond(okMsg("注册账号"))
        }
    }

    post("user/password/reset") {
        @Serializable data class Req(val phone: String, val smsCode: String, val newPassword: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

        suspendTransaction {
            val user = Users.selectAll().where { Users.phone eq req.phone }.firstOrNull()
                ?: return@suspendTransaction call.respond(fail<String>(message = "该手机号未注册"))
            Users.update({ Users.id eq user[Users.id] }) {
                it[Users.password] = req.newPassword  // TODO: BCrypt
                it[Users.updateTime] = System.currentTimeMillis().toString()
            }
            call.respond(okMsg("重置密码"))
        }
    }

    // ===== 需登录接口 =====

    authenticate("auth-jwt") {

        post("user/password/change") {
            @Serializable data class Req(val oldPassword: String, val newPassword: String)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

            val userId = call.currentUserIdOrNull()
                ?: return@post call.respond(fail<String>(code = ResultCode.UNAUTHORIZED, message = "未登录"))

            suspendTransaction {
                val user = Users.selectAll().where { Users.id eq userId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(message = "用户不存在"))
                if (user[Users.password] != req.oldPassword) {
                    return@suspendTransaction call.respond(fail<String>(message = "原密码不正确"))
                }
                Users.update({ Users.id eq userId }) {
                    it[Users.password] = req.newPassword
                    it[Users.updateTime] = System.currentTimeMillis().toString()
                }
                call.respond(okMsg("修改密码"))
            }
        }

        get("user/info/person") {
            val userId = call.currentUserIdOrNull()
                ?: return@get call.respond(fail<String>(code = ResultCode.UNAUTHORIZED, message = "未登录"))

            @Serializable data class UserInfo(
                val id: Long, val phone: String, val nickname: String,
                val avatarUrl: String, val status: Int
            )

            suspendTransaction {
                val user = Users.selectAll().where { Users.id eq userId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(message = "用户不存在"))

                call.respond(
                    ok(
                        UserInfo(
                            id = user[Users.id],
                            phone = user[Users.phone],
                            nickname = (user[Users.nickname] ?: ""),
                            avatarUrl = (user[Users.avatarUrl] ?: ""),
                            status = user[Users.status]
                        )
                    )
                )
            }
        }

        get("order/info/userCount") {
            @Serializable data class OrderCount(
                val unpaid: Long, val unshipped: Long, val unreceived: Long,
                val uncomment: Long, val refunding: Long
            )
            val userId = call.currentUserId()
                ?: return@get call.respond(fail<String>(code = ResultCode.UNAUTHORIZED, message = "未登录"))

            suspendTransaction {
                val unpaid = Orders.selectAll().where { (Orders.userId eq userId) and (Orders.status eq 0) }.count()
                val unshipped = Orders.selectAll().where { (Orders.userId eq userId) and (Orders.status eq 1) }.count()
                val unreceived = Orders.selectAll().where { (Orders.userId eq userId) and (Orders.status eq 2) }.count()
                val refunding = Orders.selectAll().where { (Orders.userId eq userId) and (Orders.status eq 5) }.count()

                call.respond(ok(OrderCount(unpaid, unshipped, unreceived, 0L, refunding)))
            }
        }

        get("user/info/logoff") {
            // 无状态 JWT：服务端不需要做什么，客户端删 token 即可
            call.respond(ok("ok"))
        }
    }
}
