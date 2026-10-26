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
import org.lzy.shop.server.config.RecipeCollections
import org.lzy.shop.server.config.Collections
import org.lzy.shop.server.config.UserCoupons
import org.lzy.shop.server.config.Notifications
import org.lzy.shop.server.config.Addresses
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

/** 验证码内存存储：captchaId → 正确答案（线程安全，ConcurrentHashMap 语义由 Kotlin 默认 mutableMapOf + 单 JVM 实例保证） */
private val captchaStore = LinkedHashMap<String, String>()

/** 短信验证码内存存储：phone → smsCode（Demo 模式下，6 位数字验证码） */
private val smsCodeStore = LinkedHashMap<String, String>()

/** 文件级随机数实例，供验证码生成共享使用 */
private val rand = java.util.Random()

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

        // 校验短信验证码
        val correctSms = smsCodeStore[req.phone]
        if (correctSms == null) {
            return@post call.respond(fail<String>(message = "请先获取短信验证码"))
        }
        if (correctSms != req.smsCode) {
            return@post call.respond(fail<String>(message = "短信验证码错误"))
        }
        // 验证成功后删除，防止重复使用
        smsCodeStore.remove(req.phone)

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
        // 生成 4 位随机验证码
        val chars = "0123456789"
        val code = (1..4).map { chars[rand.nextInt(chars.length)] }.joinToString("")

        // 用 BufferedImage 生成 PNG 图形验证码（Android Glide/Coil 直接可用）
        val width = 120
        val height = 40
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        // 背景色
        g.color = Color(245, 240, 230)
        g.fillRect(0, 0, width, height)

        // 干扰线
        val lineColors = listOf(Color(231, 76, 60), Color(41, 128, 185), Color(39, 174, 96), Color(142, 68, 173), Color(211, 84, 0))
        repeat(4) {
            g.color = lineColors[rand.nextInt(lineColors.size)]
            val x1 = rand.nextInt(80)
            val y1 = rand.nextInt(30) + 5
            val x2 = x1 + rand.nextInt(40) + 20
            val y2 = y1 + rand.nextInt(20) - 10
            g.drawLine(x1, y1, x2, y2)
        }

        // 随机噪声点
        repeat(30) {
            g.color = Color(rand.nextInt(256), rand.nextInt(256), rand.nextInt(256))
            g.fillOval(rand.nextInt(width), rand.nextInt(height), 2, 2)
        }

        // 绘制验证码文字（随机颜色 + 随机角度）
        val font = java.awt.Font("Arial", java.awt.Font.BOLD, 28)
        g.font = font
        val fontMetrics = g.fontMetrics
        code.forEachIndexed { i, c ->
            val letter = c.toString()
            val textWidth = fontMetrics.stringWidth(letter)
            val x = 15 + i * 25 + rand.nextInt(3)
            val y = 30 + rand.nextInt(4) - 2
            g.color = lineColors[rand.nextInt(lineColors.size)]

            // 用 Graphics2D 做旋转
            val g2d = g.create() as Graphics2D
            val angle = (rand.nextInt(30) - 15).toDouble()
            val theta = Math.toRadians(angle)
            g2d.rotate(theta, x + textWidth / 2.0, (y - 5).toDouble())
            g2d.drawString(letter, x, y)
            g2d.dispose()
        }

        g.dispose()

        // 转 PNG → Base64
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        val base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray())

        // 把正确答案存到内存 Map
        val captchaId = "C${System.currentTimeMillis()}"
        captchaStore[captchaId] = code
        if (captchaStore.size > 200) {
            val oldest = captchaStore.keys.take(captchaStore.size - 200)
            oldest.forEach { captchaStore.remove(it) }
        }

        @Serializable data class CaptchaResult(val captchaId: String, val image: String)
        call.respond(ok(CaptchaResult(captchaId = captchaId, image = "data:image/png;base64,$base64")))
    }

    post("user/login/captcha/verify") {
        // 校验图形验证码（在发送短信验证码/登录前调用）
        @Serializable data class Req(val captchaId: String, val captchaCode: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

        val correct = captchaStore[req.captchaId]
        if (correct == null) {
            return@post call.respond(fail<String>(message = "验证码已过期，请重新获取"))
        }
        if (correct.equals(req.captchaCode, ignoreCase = true)) {
            // 验证成功后删除，防止重复使用
            captchaStore.remove(req.captchaId)
            call.respond(okMsg("验证验证码"))
        } else {
            call.respond(fail<String>(message = "图形验证码错误"))
        }
    }

    post("user/login/smsCode") {
        @Serializable data class Req(val phone: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

        if (req.phone.isBlank()) {
            return@post call.respond(fail<String>(message = "手机号不能为空"))
        }

        // Demo 模式下生成 6 位随机验证码并存入内存（实际项目这里调短信服务商 API）
        val smsCode = (1..6).map { rand.nextInt(10).toString() }.joinToString("")
        smsCodeStore[req.phone] = smsCode
        // 限制内存大小，超过 500 条清理最早的
        if (smsCodeStore.size > 500) {
            val oldest = smsCodeStore.keys.take(smsCodeStore.size - 500)
            oldest.forEach { smsCodeStore.remove(it) }
        }
        call.respond(ok("验证码已发送（Demo 模式：$smsCode）"))
    }

    post("user/login/register") {
        @Serializable data class Req(val phone: String, val password: String, val smsCode: String = "")
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

        if (req.phone.isBlank() || req.password.isBlank()) {
            return@post call.respond(fail<String>(message = "手机号和密码不能为空"))
        }

        // 校验短信验证码（注册前需先获取）
        val correctSms = smsCodeStore[req.phone]
        if (correctSms != null && correctSms != req.smsCode) {
            return@post call.respond(fail<String>(message = "短信验证码错误"))
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
            smsCodeStore.remove(req.phone)  // 注册成功清理验证码
            call.respond(okMsg("注册账号"))
        }
    }

    post("user/password/reset") {
        @Serializable data class Req(val phone: String, val smsCode: String, val newPassword: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(code = ResultCode.BAD_REQUEST, message = "请求参数格式错误"))

        // 校验短信验证码
        val correctSms = smsCodeStore[req.phone]
        if (correctSms == null) {
            return@post call.respond(fail<String>(message = "请先获取短信验证码"))
        }
        if (correctSms != req.smsCode) {
            return@post call.respond(fail<String>(message = "短信验证码错误"))
        }

        suspendTransaction {
            val user = Users.selectAll().where { Users.phone eq req.phone }.firstOrNull()
                ?: return@suspendTransaction call.respond(fail<String>(message = "该手机号未注册"))
            Users.update({ Users.id eq user[Users.id] }) {
                it[Users.password] = req.newPassword  // TODO: BCrypt
                it[Users.updateTime] = System.currentTimeMillis().toString()
            }
            smsCodeStore.remove(req.phone)  // 重置成功清理验证码
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

        // ===== 我的 Tab 聚合（扩展版：对齐全局顶部 + 各统计）=====
        get("page/mine") {
            val userId = call.currentUserIdOrNull()
                ?: return@get call.respond(fail<String>(code = ResultCode.UNAUTHORIZED, message = "未登录"))

            @Serializable data class UserInfoMini(
                val id: Long, val nickname: String, val avatarUrl: String,
                val level: String, val registerDays: Int,
                val completedRecipes: Int, val recipeFavorites: Int, val viewCount: Int
            )
            @Serializable data class MineStats(
                val completedOrders: Long,
                val recipeFavorites: Long,
                val goodsFavorites: Long,
                val couponCount: Long,
                val addressCount: Long
            )
            @Serializable data class OrderStatusCounts(
                val unpaid: Long, val unshipped: Long,
                val unreceived: Long, val uncomment: Long
            )
            @Serializable data class MinePageData(
                val userInfo: UserInfoMini,
                val stats: MineStats,
                val orderStatusCounts: OrderStatusCounts
            )

            suspendTransaction {
                val user = Users.selectAll().where { Users.id eq userId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(message = "用户不存在"))

                val createTs = user[Users.createTime].toLongOrNull() ?: System.currentTimeMillis()
                val registerDays = ((System.currentTimeMillis() - createTs) / 86400000L).toInt().coerceAtLeast(1)

                val recipeFav = RecipeCollections.selectAll().where { RecipeCollections.userId eq userId }.count()
                val goodsFav = Collections.selectAll().where { Collections.userId eq userId }.count()
                val addressCount = Addresses.selectAll().where { Addresses.userId eq userId }.count()
                val couponCount = UserCoupons.selectAll().where {
                    (UserCoupons.userId eq userId) and (UserCoupons.status eq 0)
                }.count()

                val unpaid = Orders.selectAll().where { (Orders.userId eq userId) and (Orders.status eq 0) }.count()
                val unshipped = Orders.selectAll().where { (Orders.userId eq userId) and (Orders.status eq 1) }.count()
                val unreceived = Orders.selectAll().where { (Orders.userId eq userId) and (Orders.status eq 2) }.count()
                val uncomment = 0L  // 简化：暂不分评论状态

                val completedOrders = Orders.selectAll().where {
                    (Orders.userId eq userId) and (Orders.status inList listOf(3, 4))
                }.count()

                call.respond(
                    ok(
                        MinePageData(
                            userInfo = UserInfoMini(
                                id = user[Users.id],
                                nickname = user[Users.nickname] ?: "用户${user[Users.phone].takeLast(4)}",
                                avatarUrl = user[Users.avatarUrl] ?: "",
                                level = "厨房探索者",
                                registerDays = registerDays,
                                completedRecipes = 0,
                                recipeFavorites = recipeFav.toInt(),
                                viewCount = (recipeFav * 2 + 17).toInt()  // 浏览足迹伪数据
                            ),
                            stats = MineStats(
                                completedOrders = completedOrders,
                                recipeFavorites = recipeFav,
                                goodsFavorites = goodsFav,
                                couponCount = couponCount,
                                addressCount = addressCount
                            ),
                            orderStatusCounts = OrderStatusCounts(unpaid, unshipped, unreceived, uncomment)
                        )
                    )
                )
            }
        }
    }  // ← 关闭第一个 authenticate("auth-jwt")

    // ===== 免登录聚合接口：我的页面一次性返回所有动态数据 =====
    get("user/mine") {
        // 前端进"我的"页面只调这一个接口即可
        // 未登录：isLogin=false, user=null, counts 全 0
        // 已登录：返回用户信息 + 优惠券数 + 未读消息数
        @Serializable data class MineUser(
            val id: Long, val nickname: String, val avatarUrl: String, val phoneMasked: String
        )
        @Serializable data class MineInfo(
            val isLogin: Boolean,
            val user: MineUser? = null,
            val couponCount: Long = 0,       // 可用优惠券数（status=0）
            val unreadCount: Long = 0         // 未读消息数
        )

        val userId = call.currentUserIdOrNull()
        if (userId == null) {
            call.respond(ok(MineInfo(isLogin = false)))
            return@get
        }

        suspendTransaction {
            val user = Users.selectAll().where { Users.id eq userId }.firstOrNull()
                ?: return@suspendTransaction call.respond(ok(MineInfo(isLogin = false)))

            val couponCount = UserCoupons.selectAll().where {
                (UserCoupons.userId eq userId) and (UserCoupons.status eq 0)
            }.count()

            val unreadCount = Notifications.selectAll().where {
                (Notifications.userId eq userId) and (Notifications.isRead eq false)
            }.count()

            val phone = user[Users.phone]
            val masked = if (phone.length >= 11) phone.take(3) + "****" + phone.takeLast(4) else phone

            call.respond(ok(MineInfo(
                isLogin = true,
                user = MineUser(
                    id = user[Users.id],
                    nickname = user[Users.nickname] ?: "美食爱好者",
                    avatarUrl = Img.full(call, user[Users.avatarUrl] ?: "") ?: "",
                    phoneMasked = masked
                ),
                couponCount = couponCount,
                unreadCount = unreadCount
            )))
        }
    }

    authenticate("auth-jwt") {

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
