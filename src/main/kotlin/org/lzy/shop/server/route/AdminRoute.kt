package org.lzy.shop.server.route

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.auth.AdminAuth
import org.lzy.shop.server.auth.installAdminAuth
import org.lzy.shop.server.common.ResultCode
import org.lzy.shop.server.common.fail
import org.lzy.shop.server.common.ok
import org.lzy.shop.server.config.AppConfig
import org.lzy.shop.server.config.Coupons
import org.lzy.shop.server.config.Orders
import org.lzy.shop.server.config.Users
import org.lzy.shop.server.config.Goods as GoodsTable

/**
 * 后台管理路由 —— 以 /admin/ 开头
 *
 * 鉴权方式：Basic Auth（Authorization: Basic base64(user:pass)）
 * 用户名密码在 AppConfig.Admin 里配置
 *
 * 接口列表：
 *   POST /admin/login                管理员登录（返回 token）
 *   GET  /admin/dashboard/stats      首页统计数据
 *   GET  /admin/goods/list           商品列表
 *   POST /admin/goods/add            新增商品
 *   PUT  /admin/goods/update         更新商品
 *   DELETE /admin/goods/delete       删除商品
 *   GET  /admin/order/list           订单列表
 *   POST /admin/order/ship           订单发货
 *   GET  /admin/user/list            用户列表
 *   POST /admin/user/updateStatus    更新用户状态
 *   GET  /admin/coupon/list          优惠券列表
 *   POST /admin/coupon/add           新增优惠券
 */
fun Route.configureAdminRoutes() {

    // ===== 鉴权拦截：除 login 外全部检查 token =====
    installAdminAuth()

    // ===== 登录 =====
    post("login") {
        @Serializable data class Req(val username: String, val password: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

        if (req.username == AppConfig.Admin.USERNAME && req.password == AppConfig.Admin.PASSWORD) {
            val token = AdminAuth.issueToken(req.username)
            call.respond(ok(mapOf("token" to token, "username" to req.username)))
        } else {
            call.respond(fail<String>(ResultCode.UNAUTHORIZED, "用户名或密码错误"))
        }
    }

    // ===== 统计 =====
    get("dashboard/stats") {
        suspendTransaction {
            val goodsCount = GoodsTable.selectAll().count()
            val orderCount = Orders.selectAll().count()
            val userCount = Users.selectAll().count()
            val couponCount = Coupons.selectAll().count()
            val paidOrders = Orders.selectAll().where { Orders.status eq 1 }.count()
            val totalSales = Orders.selectAll().where { Orders.status inList listOf(1, 2, 3) }
                .sumOf { it[Orders.totalPrice] }

            call.respond(
                ok(
                    mapOf(
                        "goodsCount" to goodsCount,
                        "orderCount" to orderCount,
                        "userCount" to userCount,
                        "couponCount" to couponCount,
                        "paidOrders" to paidOrders,
                        "totalSales" to totalSales
                    )
                )
            )
        }
    }

    // ===== 商品管理 =====
    get("goods/list") {
        @Serializable data class Req(val keyword: String = "", val page: Int = 1, val size: Int = 20)
        val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

        suspendTransaction {
            val total = if (req.keyword.isNotBlank()) {
                GoodsTable.selectAll().where { GoodsTable.title like "%${req.keyword}%" }.count()
            } else {
                GoodsTable.selectAll().count()
            }

            val list = if (req.keyword.isNotBlank()) {
                GoodsTable.selectAll().where { GoodsTable.title like "%${req.keyword}%" }
            } else {
                GoodsTable.selectAll()
            }.orderBy(GoodsTable.id, SortOrder.DESC)
                .limit(req.size.coerceAtLeast(1)).offset(((req.page - 1) * req.size.coerceAtLeast(1)).toLong())
                .map { row ->
                    mapOf(
                        "id" to row[GoodsTable.id],
                        "typeId" to row[GoodsTable.typeId],
                        "title" to row[GoodsTable.title],
                        "mainPic" to row[GoodsTable.mainPic],
                        "price" to row[GoodsTable.price],
                        "sold" to row[GoodsTable.sold],
                        "status" to row[GoodsTable.status],
                        "createTime" to row[GoodsTable.createTime]
                    )
                }

            call.respond(ok(mapOf("total" to total, "list" to list)))
        }
    }

    post("goods/add") {
        @Serializable data class Req(
            val typeId: Long, val title: String, val subTitle: String? = null,
            val mainPic: String, val pics: String = "[]",
            val price: Int, val sold: Int = 0,
            val contentPics: String = "[]",
            val recommend: Boolean = false, val featured: Boolean = false,
            val status: Int = 1, val sortNum: Int = 0
        )
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

        val now = System.currentTimeMillis().toString()

        suspendTransaction {
            GoodsTable.insert {
                it[GoodsTable.typeId] = req.typeId
                it[GoodsTable.title] = req.title
                it[GoodsTable.subTitle] = req.subTitle
                it[GoodsTable.mainPic] = req.mainPic
                it[GoodsTable.pics] = req.pics
                it[GoodsTable.price] = req.price
                it[GoodsTable.sold] = req.sold
                it[GoodsTable.contentPics] = req.contentPics
                it[GoodsTable.recommend] = req.recommend
                it[GoodsTable.featured] = req.featured
                it[GoodsTable.status] = req.status
                it[GoodsTable.sortNum] = req.sortNum
                it[GoodsTable.createTime] = now
                it[GoodsTable.updateTime] = now
            }

            call.respond(ok("添加成功"))
        }
    }

    put("goods/update") {
        @Serializable data class Req(
            val id: Long, val typeId: Long? = null, val title: String? = null,
            val subTitle: String? = null, val mainPic: String? = null,
            val price: Int? = null, val recommend: Boolean? = null,
            val featured: Boolean? = null, val status: Int? = null,
            val sortNum: Int? = null
        )
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@put call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

        val now = System.currentTimeMillis().toString()

        suspendTransaction {
            val updated = GoodsTable.update({ GoodsTable.id eq req.id }) {
                req.typeId?.let { v -> it[GoodsTable.typeId] = v }
                req.title?.let { v -> it[GoodsTable.title] = v }
                req.subTitle?.let { v -> it[GoodsTable.subTitle] = v }
                req.mainPic?.let { v -> it[GoodsTable.mainPic] = v }
                req.price?.let { v -> it[GoodsTable.price] = v }
                req.recommend?.let { v -> it[GoodsTable.recommend] = v }
                req.featured?.let { v -> it[GoodsTable.featured] = v }
                req.status?.let { v -> it[GoodsTable.status] = v }
                req.sortNum?.let { v -> it[GoodsTable.sortNum] = v }
                it[GoodsTable.updateTime] = now
            }

            if (updated == 0) {
                call.respond(fail<String>(ResultCode.NOT_FOUND, "商品不存在"))
            } else {
                call.respond(ok("更新成功"))
            }
        }
    }

    delete("goods/delete") {
        @Serializable data class Req(val id: Long)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@delete call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

        suspendTransaction {
            // 软删除：把状态改成 0（下架）
            val updated = GoodsTable.update({ GoodsTable.id eq req.id }) {
                it[GoodsTable.status] = 0
            }

            if (updated == 0) {
                call.respond(fail<String>(ResultCode.NOT_FOUND, "商品不存在"))
            } else {
                call.respond(ok("删除成功"))
            }
        }
    }

    // ===== 订单管理 =====
    get("order/list") {
        @Serializable data class Req(val status: Int? = null, val page: Int = 1, val size: Int = 20)
        val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

        suspendTransaction {
            val total = if (req.status != null) {
                Orders.selectAll().where { Orders.status eq req.status }.count()
            } else {
                Orders.selectAll().count()
            }

            val query = if (req.status != null) {
                Orders.selectAll().where { Orders.status eq req.status }
            } else {
                Orders.selectAll()
            }

            val list = query.orderBy(Orders.createTime, SortOrder.DESC)
                .limit(req.size.coerceAtLeast(1)).offset(((req.page - 1) * req.size.coerceAtLeast(1)).toLong())
                .map { row ->
                    mapOf(
                        "id" to row[Orders.id],
                        "orderNo" to row[Orders.orderNo],
                        "userId" to row[Orders.userId],
                        "totalPrice" to row[Orders.totalPrice],
                        "status" to row[Orders.status],
                        "remark" to row[Orders.remark],
                        "createTime" to row[Orders.createTime],
                        "payTime" to row[Orders.payTime],
                        "shipTime" to row[Orders.shipTime]
                    )
                }

            call.respond(ok(mapOf("total" to total, "list" to list)))
        }
    }

    post("order/ship") {
        @Serializable data class Req(val orderId: Long)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

        suspendTransaction {
            val updated = Orders.update({ Orders.id eq req.orderId }) {
                it[Orders.status] = 2  // 已发货
                it[Orders.shipTime] = System.currentTimeMillis().toString()
            }

            if (updated == 0) {
                call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))
            } else {
                call.respond(ok("发货成功"))
            }
        }
    }

    // ===== 用户管理 =====
    get("user/list") {
        @Serializable data class Req(val keyword: String = "", val page: Int = 1, val size: Int = 20)
        val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

        suspendTransaction {
            val total = Users.selectAll().count()

            val list = Users.selectAll()
                .orderBy(Users.id, SortOrder.DESC)
                .limit(req.size.coerceAtLeast(1)).offset(((req.page - 1) * req.size.coerceAtLeast(1)).toLong())
                .map { row ->
                    mapOf(
                        "id" to row[Users.id],
                        "phone" to row[Users.phone],
                        "nickname" to (row[Users.nickname] ?: ""),
                        "status" to row[Users.status],
                        "createTime" to row[Users.createTime]
                    )
                }

            call.respond(ok(mapOf("total" to total, "list" to list)))
        }
    }

    post("user/updateStatus") {
        @Serializable data class Req(val userId: Long, val status: Int)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

        suspendTransaction {
            val updated = Users.update({ Users.id eq req.userId }) {
                it[Users.status] = req.status
                it[Users.updateTime] = System.currentTimeMillis().toString()
            }

            if (updated == 0) {
                call.respond(fail<String>(ResultCode.NOT_FOUND, "用户不存在"))
            } else {
                call.respond(ok("更新成功"))
            }
        }
    }

    // ===== 优惠券管理 =====
    get("coupon/list") {
        suspendTransaction {
            val list = Coupons.selectAll()
                .orderBy(Coupons.id, SortOrder.DESC)
                .map { row ->
                    mapOf(
                        "id" to row[Coupons.id],
                        "name" to row[Coupons.name],
                        "discount" to row[Coupons.discount],
                        "minAmount" to row[Coupons.minAmount],
                        "remainCount" to row[Coupons.remainCount],
                        "startTime" to row[Coupons.startTime],
                        "endTime" to row[Coupons.endTime],
                        "status" to row[Coupons.status]
                    )
                }

            call.respond(ok(list))
        }
    }

    post("coupon/add") {
        @Serializable data class Req(
            val name: String, val discount: Int, val minAmount: Int = 0,
            val totalCount: Int = 0, val startTime: String, val endTime: String,
            val status: Int = 1
        )
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

        suspendTransaction {
            Coupons.insert {
                it[Coupons.name] = req.name
                it[Coupons.discount] = req.discount
                it[Coupons.minAmount] = req.minAmount
                it[Coupons.totalCount] = req.totalCount
                it[Coupons.remainCount] = req.totalCount
                it[Coupons.startTime] = req.startTime
                it[Coupons.endTime] = req.endTime
                it[Coupons.status] = req.status
            }

            call.respond(ok("添加成功"))
        }
    }
}