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
import org.lzy.shop.server.common.*
import org.lzy.shop.server.config.AppConfig
import org.lzy.shop.server.config.Banners
import org.lzy.shop.server.config.Categories
import org.lzy.shop.server.config.Coupons
import org.lzy.shop.server.config.OrderItems
import org.lzy.shop.server.config.Orders
import org.lzy.shop.server.config.SearchKeywords
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
        @Serializable data class LoginResult(val token: String, val username: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        if (req.username == AppConfig.Admin.USERNAME && req.password == AppConfig.Admin.PASSWORD) {
            val token = AdminAuth.issueToken(req.username)
            call.respond(ok(LoginResult(token = token, username = req.username)))
        } else {
            call.respond(fail<String>(ResultCode.UNAUTHORIZED, "用户名或密码错误"))
        }
    }

    // ===== 统计（含图表数据） =====
    get("dashboard/stats") {
        @Serializable data class DaySales(val date: String, val amount: Long)
        @Serializable data class StatusCount(val status: Int, val label: String, val count: Long)
        @Serializable data class TopGoods(val id: Long, val title: String, val pic: String, val sold: Long)
        @Serializable data class RecentOrder(
            val id: Long, val orderNo: String, val userId: Long,
            val totalPrice: Int, val status: Int, val createTime: String
        )
        @Serializable data class DashboardStats(
            val goodsCount: Long,
            val orderCount: Long,
            val userCount: Long,
            val couponCount: Long,
            val paidOrders: Long,
            val totalSales: Long,
            val sales7Days: List<DaySales>? = null,
            val orderStatusDist: List<StatusCount>? = null,
            val topGoods: List<TopGoods>? = null,
            val recentOrders: List<RecentOrder>? = null
        )

        suspendTransaction {
            val goodsCount = GoodsTable.selectAll().count()
            val orderCount = Orders.selectAll().count()
            val userCount = Users.selectAll().count()
            val couponCount = Coupons.selectAll().count()
            val paidOrders = Orders.selectAll().where { Orders.status eq 1 }.count()
            val totalSales = Orders.selectAll().where { Orders.status inList listOf(1, 2, 3) }
                .sumOf { it[Orders.totalPrice] }.toLong()

            // --- 近7天每日销售额（客户端按 createTime 毫秒时间戳分组） ---
            val nowMs = System.currentTimeMillis()
            val dayMs = 24 * 60 * 60 * 1000L
            val daySalesMap = LinkedHashMap<String, Long>()
            val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
            for (i in 6 downTo 0) {
                val dayStr = java.time.Instant.ofEpochMilli(nowMs - i * dayMs)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(fmt)
                daySalesMap[dayStr] = 0L
            }
            Orders.selectAll().where { Orders.status inList listOf(1, 2, 3) }.forEach { row ->
                val ct = row[Orders.createTime].toLongOrNull() ?: return@forEach
                if (nowMs - ct <= 7 * dayMs) {
                    val dayStr = java.time.Instant.ofEpochMilli(ct)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(fmt)
                    daySalesMap[dayStr] = (daySalesMap[dayStr] ?: 0L) + row[Orders.totalPrice]
                }
            }
            val sales7Days = daySalesMap.map { (date, amount) -> DaySales(date, amount) }

            // --- 订单状态分布 ---
            val statusLabels = mapOf(0 to "待支付", 1 to "已支付", 2 to "已发货", 3 to "已签收", 4 to "已取消")
            val statusDist = Orders.select(Orders.status, Orders.id.count())
                .groupBy(Orders.status)
                .associate { it[Orders.status] to it[Orders.id.count()] }
            val orderStatusDist = statusLabels.keys.sorted().map { s ->
                StatusCount(s, statusLabels[s]!!, statusDist[s] ?: 0L)
            }

            // --- 热销商品 TOP5 ---
            val topGoods = GoodsTable.selectAll()
                .orderBy(GoodsTable.sold, SortOrder.DESC)
                .limit(5)
                .map { row ->
                    TopGoods(
                        id = row[GoodsTable.id],
                        title = row[GoodsTable.title],
                        pic = row[GoodsTable.mainPic],
                        sold = row[GoodsTable.sold].toLong()
                    )
                }

            // --- 最近 5 条订单 ---
            val recentOrders = Orders.selectAll()
                .orderBy(Orders.id, SortOrder.DESC)
                .limit(5)
                .map { row ->
                    RecentOrder(
                        id = row[Orders.id],
                        orderNo = row[Orders.orderNo],
                        userId = row[Orders.userId],
                        totalPrice = row[Orders.totalPrice],
                        status = row[Orders.status],
                        createTime = row[Orders.createTime]
                    )
                }

            call.respond(ok(DashboardStats(
                goodsCount, orderCount, userCount, couponCount, paidOrders, totalSales,
                sales7Days, orderStatusDist, topGoods, recentOrders
            )))
        }
    }

    // ===== 商品管理 =====
    get("goods/list") {
        val keyword = call.request.queryParameters["keyword"] ?: ""
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1

        @Serializable data class GoodsItem(
            val id: Long, val typeId: Long, val title: String,
            val mainPic: String, val price: Int, val sold: Int,
            val status: Int, val createTime: String
        )

        suspendTransaction {
            val total = if (keyword.isNotBlank()) {
                GoodsTable.selectAll().where { GoodsTable.title like "%$keyword%" }.count()
            } else {
                GoodsTable.selectAll().count()
            }

            val list = if (keyword.isNotBlank()) {
                GoodsTable.selectAll().where { GoodsTable.title like "%$keyword%" }
            } else {
                GoodsTable.selectAll()
            }.orderBy(GoodsTable.id, SortOrder.DESC)
                .limit(10).offset(((page - 1) * 10).toLong())
                .map { row ->
                    GoodsItem(
                        id = row[GoodsTable.id],
                        typeId = row[GoodsTable.typeId],
                        title = row[GoodsTable.title],
                        mainPic = row[GoodsTable.mainPic],
                        price = row[GoodsTable.price],
                        sold = row[GoodsTable.sold],
                        status = row[GoodsTable.status],
                        createTime = row[GoodsTable.createTime]
                    )
                }

            call.respond(ok(PageResult(total, list)))
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
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

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

            call.respond(okMsg("新增商品"))
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
            ?: return@put call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

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
                    call.respond(okMsg("更新商品"))
                }
            }
        }

        delete("goods/delete") {
        @Serializable data class Req(val id: Long)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@delete call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            // 软删除：把状态改成 0（下架）
            val updated = GoodsTable.update({ GoodsTable.id eq req.id }) {
                it[GoodsTable.status] = 0
            }

            if (updated == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "商品不存在"))
                } else {
                    call.respond(okMsg("删除商品"))
                }
            }
        }

        // ===== 订单管理 =====
    get("order/list") {
        val status = call.request.queryParameters["status"]?.toIntOrNull()
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1

        @Serializable data class OrderItem(
            val id: Long, val orderNo: String, val userId: Long,
            val totalPrice: Int, val status: Int, val remark: String,
            val createTime: String, val payTime: String?, val shipTime: String?
        )

        suspendTransaction {
            val total = if (status != null) {
                Orders.selectAll().where { Orders.status eq status }.count()
            } else {
                Orders.selectAll().count()
            }

            val query = if (status != null) {
                Orders.selectAll().where { Orders.status eq status }
            } else {
                Orders.selectAll()
            }

            val list = query.orderBy(Orders.createTime, SortOrder.DESC)
                .limit(10).offset(((page - 1) * 10).toLong())
                .map { row ->
                    OrderItem(
                        id = row[Orders.id],
                        orderNo = row[Orders.orderNo],
                        userId = row[Orders.userId],
                        totalPrice = row[Orders.totalPrice],
                        status = row[Orders.status],
                        remark = row[Orders.remark],
                        createTime = row[Orders.createTime],
                        payTime = row[Orders.payTime],
                        shipTime = row[Orders.shipTime]
                    )
                }

            call.respond(ok(PageResult(total, list)))
        }
    }

    post("order/ship") {
        @Serializable data class Req(val orderId: Long)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            val updated = Orders.update({ Orders.id eq req.orderId }) {
                it[Orders.status] = 2  // 已发货
                it[Orders.shipTime] = System.currentTimeMillis().toString()
            }

            if (updated == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))
                } else {
                    call.respond(okMsg("订单发货"))
                }
            }
        }

    get("order/detail") {
        val orderId = call.request.queryParameters["id"]?.toLongOrNull()
            ?: return@get call.respond(fail<String>(ResultCode.BAD_REQUEST, "id 参数无效"))

        @Serializable data class OrderGoods(
            val goodsId: Long, val title: String, val mainPic: String,
            val specInfo: String? = null, val price: Int, val quantity: Int
        )
        @Serializable data class OrderDetail(
            val id: Long, val orderNo: String, val userId: Long, val status: Int,
            val totalPrice: Int, val payPrice: Int, val couponId: Long?,
            val remark: String, val address: String,
            val createTime: String, val payTime: String?, val shipTime: String?,
            val receiveTime: String?, val goodsList: List<OrderGoods>
        )

        suspendTransaction {
            val orderRow = Orders.selectAll().where { Orders.id eq orderId }.firstOrNull()
                ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))

            val goodsList = OrderItems.selectAll().where { OrderItems.orderId eq orderId }
                .map { item ->
                    OrderGoods(
                        goodsId = item[OrderItems.goodsId],
                        title = item[OrderItems.goodsTitle],
                        mainPic = item[OrderItems.goodsMainPic],
                        specInfo = item[OrderItems.specInfo],
                        price = item[OrderItems.price],
                        quantity = item[OrderItems.quantity]
                    )
                }

            call.respond(
                ok(
                    OrderDetail(
                        id = orderRow[Orders.id],
                        orderNo = orderRow[Orders.orderNo],
                        userId = orderRow[Orders.userId],
                        status = orderRow[Orders.status],
                        totalPrice = orderRow[Orders.totalPrice],
                        payPrice = orderRow[Orders.payPrice],
                        couponId = orderRow[Orders.couponId],
                        remark = orderRow[Orders.remark],
                        address = orderRow[Orders.addressSnapshot],
                        createTime = orderRow[Orders.createTime],
                        payTime = orderRow[Orders.payTime],
                        shipTime = orderRow[Orders.shipTime],
                        receiveTime = orderRow[Orders.receiveTime],
                        goodsList = goodsList
                    )
                )
            )
        }
    }

        // ===== 用户管理 =====
    get("user/list") {
        val keyword = call.request.queryParameters["keyword"] ?: ""
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1

        @Serializable data class UserItem(
            val id: Long, val phone: String, val nickname: String,
            val status: Int, val createTime: String
        )

        suspendTransaction {
            val total = if (keyword.isNotBlank()) {
                Users.selectAll().where { Users.phone like "%$keyword%" }.count()
            } else {
                Users.selectAll().count()
            }

            val list = if (keyword.isNotBlank()) {
                Users.selectAll().where { Users.phone like "%$keyword%" }
            } else {
                Users.selectAll()
            }.orderBy(Users.id, SortOrder.DESC)
                .limit(10).offset(((page - 1) * 10).toLong())
                .map { row ->
                    UserItem(
                        id = row[Users.id],
                        phone = row[Users.phone],
                        nickname = (row[Users.nickname] ?: ""),
                        status = row[Users.status],
                        createTime = row[Users.createTime]
                    )
                }

            call.respond(ok(PageResult(total, list)))
        }
    }

    post("user/updateStatus") {
        @Serializable data class Req(val userId: Long, val status: Int)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            val updated = Users.update({ Users.id eq req.userId }) {
                it[Users.status] = req.status
                it[Users.updateTime] = System.currentTimeMillis().toString()
            }

            if (updated == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "用户不存在"))
                } else {
                    call.respond(okMsg("更新用户状态"))
                }
        }
    }

    // ===== 优惠券管理 =====
    get("coupon/list") {
        @Serializable data class CouponItem(
            val id: Long, val name: String, val discount: Int,
            val minAmount: Int, val remainCount: Int,
            val startTime: String, val endTime: String, val status: Int
        )

        suspendTransaction {
            val list = Coupons.selectAll()
                .orderBy(Coupons.id, SortOrder.DESC)
                .map { row ->
                    CouponItem(
                        id = row[Coupons.id],
                        name = row[Coupons.name],
                        discount = row[Coupons.discount],
                        minAmount = row[Coupons.minAmount],
                        remainCount = row[Coupons.remainCount],
                        startTime = row[Coupons.startTime],
                        endTime = row[Coupons.endTime],
                        status = row[Coupons.status]
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
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

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

            call.respond(okMsg("新增优惠券"))
        }
    }

    put("coupon/update") {
        @Serializable data class Req(
            val id: Long, val name: String? = null, val discount: Int? = null,
            val minAmount: Int? = null, val totalCount: Int? = null,
            val startTime: String? = null, val endTime: String? = null, val status: Int? = null
        )
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@put call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            val updated = Coupons.update({ Coupons.id eq req.id }) {
                req.name?.let { v -> it[Coupons.name] = v }
                req.discount?.let { v -> it[Coupons.discount] = v }
                req.minAmount?.let { v -> it[Coupons.minAmount] = v }
                req.totalCount?.let { v -> it[Coupons.totalCount] = v }
                req.startTime?.let { v -> it[Coupons.startTime] = v }
                req.endTime?.let { v -> it[Coupons.endTime] = v }
                req.status?.let { v -> it[Coupons.status] = v }
            }
            if (updated == 0) call.respond(fail<String>(ResultCode.NOT_FOUND, "优惠券不存在"))
            else call.respond(okMsg("更新优惠券"))
        }
    }

    delete("coupon/delete") {
        @Serializable data class Req(val id: Long)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@delete call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            val deleted = Coupons.deleteWhere { Coupons.id eq req.id }
            if (deleted == 0) call.respond(fail<String>(ResultCode.NOT_FOUND, "优惠券不存在"))
            else call.respond(okMsg("删除优惠券"))
        }
    }

    // ===== 分类管理 =====
    get("category/list") {
        @Serializable data class CategoryItem(
            val id: Long, val name: String, val parentId: Long,
            val pic: String, val sortNum: Int
        )
        suspendTransaction {
            val list = Categories.selectAll()
                .orderBy(Categories.parentId, SortOrder.ASC)
                .orderBy(Categories.sortNum, SortOrder.ASC)
                .map { row ->
                    CategoryItem(
                        id = row[Categories.id],
                        name = row[Categories.name],
                        parentId = row[Categories.parentId],
                        pic = (row[Categories.pic] ?: ""),
                        sortNum = row[Categories.sortNum]
                    )
                }
            call.respond(ok(list))
        }
    }

    post("category/add") {
        @Serializable data class Req(val name: String, val parentId: Long = 0, val pic: String = "", val sortNum: Int = 0)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            Categories.insert {
                it[Categories.name] = req.name
                it[Categories.parentId] = req.parentId
                it[Categories.pic] = req.pic
                it[Categories.sortNum] = req.sortNum
            }
            call.respond(okMsg("新增分类"))
        }
    }

    put("category/update") {
        @Serializable data class Req(val id: Long, val name: String? = null, val pic: String? = null, val sortNum: Int? = null)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@put call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            val updated = Categories.update({ Categories.id eq req.id }) {
                req.name?.let { v -> it[Categories.name] = v }
                req.pic?.let { v -> it[Categories.pic] = v }
                req.sortNum?.let { v -> it[Categories.sortNum] = v }
            }
            if (updated == 0) call.respond(fail<String>(ResultCode.NOT_FOUND, "分类不存在"))
            else call.respond(okMsg("修改分类"))
        }
    }

    delete("category/delete") {
        @Serializable data class Req(val id: Long)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@delete call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            val hasChild = Categories.selectAll().where { Categories.parentId eq req.id }.count()
            if (hasChild > 0) {
                return@suspendTransaction call.respond(fail<String>(ResultCode.BUSINESS_ERROR, "存在子分类，无法删除"))
            }
            val hasGoods = GoodsTable.selectAll().where { GoodsTable.typeId eq req.id }.count()
            if (hasGoods > 0) {
                return@suspendTransaction call.respond(fail<String>(ResultCode.BUSINESS_ERROR, "分类下存在商品，无法删除"))
            }
            val deleted = Categories.deleteWhere { Categories.id eq req.id }
            if (deleted == 0) call.respond(fail<String>(ResultCode.NOT_FOUND, "分类不存在"))
            else call.respond(okMsg("删除分类"))
        }
    }

    // ===== 轮播图管理 =====
    get("banner/list") {
        @Serializable data class BannerItem(
            val id: Long, val pic: String, val url: String,
            val title: String, val sortNum: Int, val status: Int
        )
        suspendTransaction {
            val list = Banners.selectAll()
                .orderBy(Banners.sortNum, SortOrder.ASC)
                .map { row ->
                    BannerItem(
                        id = row[Banners.id],
                        pic = row[Banners.pic],
                        url = (row[Banners.url] ?: ""),
                        title = (row[Banners.title] ?: ""),
                        sortNum = row[Banners.sortNum],
                        status = row[Banners.status]
                    )
                }
            call.respond(ok(list))
        }
    }

    post("banner/add") {
        @Serializable data class Req(val pic: String, val url: String = "", val title: String = "", val sortNum: Int = 0, val status: Int = 1)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            Banners.insert {
                it[Banners.pic] = req.pic
                it[Banners.url] = req.url
                it[Banners.title] = req.title
                it[Banners.sortNum] = req.sortNum
                it[Banners.status] = req.status
            }
            call.respond(okMsg("新增轮播图"))
        }
    }

    put("banner/update") {
        @Serializable data class Req(val id: Long, val pic: String? = null, val url: String? = null, val title: String? = null, val sortNum: Int? = null, val status: Int? = null)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@put call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            val updated = Banners.update({ Banners.id eq req.id }) {
                req.pic?.let { v -> it[Banners.pic] = v }
                req.url?.let { v -> it[Banners.url] = v }
                req.title?.let { v -> it[Banners.title] = v }
                req.sortNum?.let { v -> it[Banners.sortNum] = v }
                req.status?.let { v -> it[Banners.status] = v }
            }
            if (updated == 0) call.respond(fail<String>(ResultCode.NOT_FOUND, "轮播图不存在"))
            else call.respond(okMsg("修改轮播图"))
        }
    }

    delete("banner/delete") {
        @Serializable data class Req(val id: Long)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@delete call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            val deleted = Banners.deleteWhere { Banners.id eq req.id }
            if (deleted == 0) call.respond(fail<String>(ResultCode.NOT_FOUND, "轮播图不存在"))
            else call.respond(okMsg("删除轮播图"))
        }
    }

    // ===== 搜索关键词管理 =====
    get("keyword/list") {
        @Serializable data class KeywordItem(
            val id: Long, val keyword: String, val searchCount: Int,
            val isHot: Boolean, val sortNum: Int
        )
        suspendTransaction {
            val list = SearchKeywords.selectAll()
                .orderBy(SearchKeywords.sortNum, SortOrder.ASC)
                .map { row ->
                    KeywordItem(
                        id = row[SearchKeywords.id],
                        keyword = row[SearchKeywords.keyword],
                        searchCount = row[SearchKeywords.searchCount],
                        isHot = row[SearchKeywords.isHot],
                        sortNum = row[SearchKeywords.sortNum]
                    )
                }
            call.respond(ok(list))
        }
    }

    post("keyword/add") {
        @Serializable data class Req(val keyword: String, val isHot: Boolean = false, val sortNum: Int = 0)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            val exists = SearchKeywords.selectAll().where { SearchKeywords.keyword eq req.keyword }.firstOrNull()
            if (exists != null) return@suspendTransaction call.respond(fail<String>(ResultCode.BUSINESS_ERROR, "关键词已存在"))

            SearchKeywords.insert {
                it[SearchKeywords.keyword] = req.keyword
                it[SearchKeywords.isHot] = req.isHot
                it[SearchKeywords.sortNum] = req.sortNum
            }
            call.respond(okMsg("新增关键词"))
        }
    }

    delete("keyword/delete") {
        @Serializable data class Req(val id: Long)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@delete call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        suspendTransaction {
            val deleted = SearchKeywords.deleteWhere { SearchKeywords.id eq req.id }
            if (deleted == 0) call.respond(fail<String>(ResultCode.NOT_FOUND, "关键词不存在"))
            else call.respond(okMsg("删除关键词"))
        }
    }
}