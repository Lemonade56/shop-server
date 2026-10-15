package org.lzy.shop.server.route

import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.auth.currentUserId
import org.lzy.shop.server.common.ResultCode
import org.lzy.shop.server.common.fail
import org.lzy.shop.server.common.ok
import org.lzy.shop.server.config.Goods as GoodsTable
import org.lzy.shop.server.config.OrderItems
import org.lzy.shop.server.config.Orders

/**
 * 订单相关路由 —— 对齐 App 端 OrderApi.kt
 *
 * 接口列表（均需登录，除了字典数据）：
 *   POST app/order/info/create          创建订单
 *   POST app/order/info/page            订单列表（分页 + 状态筛选）
 *   GET  app/page/confirmOrder          确认订单页数据
 *   POST app/order/pay/alipayAppPay     发起支付宝支付（占位）
 *   GET  app/order/info/logistics       物流信息（占位）
 *   POST app/order/info/cancel          取消订单
 *   POST app/order/info/refund          申请退款
 *   GET  app/order/info/info            订单详情
 *   GET  app/order/info/confirm         确认收货
 *   POST app/dict/info/data             字典数据（公共）
 *   POST app/goods/comment/submit       提交商品评论（占位）
 */
fun Route.configureOrderRoutes() {

    // ===== 字典数据（公共） =====
    post("dict/info/data") {
        @Serializable data class Req(val type: String)
        val req = runCatching { call.receive<Req>() }.getOrNull()

        val data = when (req?.type) {
            "order_cancel_reason" -> listOf(
                mapOf("id" to 1, "name" to "不想要了"),
                mapOf("id" to 2, "name" to "信息填写错误"),
                mapOf("id" to 3, "name" to "商品缺货"),
                mapOf("id" to 4, "name" to "其他原因")
            )
            "order_refund_reason" -> listOf(
                mapOf("id" to 1, "name" to "商品质量问题"),
                mapOf("id" to 2, "name" to "商品与描述不符"),
                mapOf("id" to 3, "name" to "发错货"),
                mapOf("id" to 4, "name" to "其他")
            )
            else -> emptyList<Any>()
        }
        call.respond(ok(data))
    }

    authenticate("auth-jwt") {

        post("order/info/create") {
            @Serializable data class SelectedGoods(val goodsId: Long, val quantity: Int, val specId: Long? = null)
            @Serializable data class CreateOrder(
                val addressId: Long, val goodsList: List<SelectedGoods>,
                val couponId: Long? = null, val remark: String = "", val title: String = ""
            )
            @Serializable data class Req(val data: CreateOrder)

            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

            val userId = call.currentUserId()
            val orderNo = "SN${System.currentTimeMillis()}"
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                // 计算总价 + 构建商品项
                var totalPrice = 0
                val items = mutableListOf<OrderItemData>()

                req.data.goodsList.forEach { sg ->
                    val g = GoodsTable.selectAll().where { GoodsTable.id eq sg.goodsId }.firstOrNull()
                        ?: return@suspendTransaction call.respond(fail<String>(ResultCode.BAD_REQUEST, "商品不存在 #${sg.goodsId}"))

                    val price = g[GoodsTable.price]
                    totalPrice += price * sg.quantity
                    items.add(
                        OrderItemData(
                            goodsId = sg.goodsId,
                            goodsTitle = g[GoodsTable.title],
                            goodsMainPic = g[GoodsTable.mainPic],
                            specInfo = sg.specId?.toString(),
                            price = price,
                            quantity = sg.quantity
                        )
                    )
                }

                // 创建订单（地址快照暂时用空，后续可从 Addresses 表取）
                val orderId = Orders.insert {
                    it[Orders.orderNo] = orderNo
                    it[Orders.userId] = userId
                    it[Orders.addressSnapshot] = "{}"
                    it[Orders.totalPrice] = totalPrice
                    it[Orders.payPrice] = totalPrice
                    it[Orders.couponId] = req.data.couponId
                    it[Orders.status] = 0  // 待支付
                    it[Orders.remark] = req.data.remark
                    it[Orders.createTime] = now
                } get Orders.id

                // 创建订单商品项
                items.forEach { item ->
                    OrderItems.insert {
                        it[OrderItems.orderId] = orderId
                        it[OrderItems.goodsId] = item.goodsId
                        it[OrderItems.goodsTitle] = item.goodsTitle
                        it[OrderItems.goodsMainPic] = item.goodsMainPic
                        it[OrderItems.specInfo] = item.specInfo
                        it[OrderItems.price] = item.price
                        it[OrderItems.quantity] = item.quantity
                    }
                }

                call.respond(
                    ok(
                        mapOf(
                            "orderNo" to orderNo,
                            "orderId" to orderId
                        )
                    )
                )
            }
        }

        post("order/info/page") {
            @Serializable data class Req(val page: Int = 1, val size: Int = 10, val status: List<Int>? = null)
            val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

            @Serializable data class OrderGoods(
                val goodsId: Long, val title: String, val mainPic: String,
                val specInfo: String? = null, val price: Int, val quantity: Int
            )
            @Serializable data class OrderItem(
                val id: Long, val orderNo: String, val status: Int, val totalPrice: Int,
                val createTime: String, val goodsList: List<OrderGoods>
            )

            val userId = call.currentUserId()

            suspendTransaction {
                val baseQuery = if (req.status != null && req.status.isNotEmpty()) {
                    Orders.selectAll().where { (Orders.userId eq userId) and (Orders.status inList req.status) }
                } else {
                    Orders.selectAll().where { Orders.userId eq userId }
                }

                val total = baseQuery.count()

                val orderRows = baseQuery
                    .orderBy(Orders.createTime, SortOrder.DESC)
                    .limit(req.size.coerceAtLeast(1)).offset(((req.page - 1) * req.size.coerceAtLeast(1)).toLong())
                    .toList()

                val list = orderRows.map { orderRow ->
                    val oid = orderRow[Orders.id]
                    val goodsList = OrderItems.selectAll().where { OrderItems.orderId eq oid }
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

                    OrderItem(
                        id = oid,
                        orderNo = orderRow[Orders.orderNo],
                        status = orderRow[Orders.status],
                        totalPrice = orderRow[Orders.totalPrice],
                        createTime = orderRow[Orders.createTime],
                        goodsList = goodsList
                    )
                }

                call.respond(ok(mapOf("total" to total, "list" to list)))
            }
        }

        get("page/confirmOrder") {
            // 确认订单页：返回空占位（购物车功能暂未实现，需要购物车表）
            call.respond(
                ok(
                    mapOf(
                        "addressList" to emptyList<Any>(),
                        "goodsList" to emptyList<Any>(),
                        "couponList" to emptyList<Any>()
                    )
                )
            )
        }

        post("order/pay/alipayAppPay") {
            @Serializable data class Req(val orderId: Long, val payType: String = "alipay")
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

            suspendTransaction {
                // 标记订单为已支付
                val updated = Orders.update({ Orders.id eq req.orderId }) {
                    it[Orders.status] = 1
                    it[Orders.payTime] = System.currentTimeMillis().toString()
                }

                if (updated == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))
                } else {
                    val payInfo = "app_id=DEMO&biz_content=orderId=${req?.orderId}&sign=DEMO_SIGN"
                    call.respond(ok(payInfo))
                }
            }
        }

        get("order/info/logistics") {
            val orderId = call.request.queryParameters["orderId"]?.toLongOrNull()
            @Serializable data class LogisticsItem(val time: String, val content: String)

            call.respond(
                ok(
                    mapOf(
                        "company" to "顺丰速运",
                        "companyCode" to "SF",
                        "trackingNo" to "SF1234567890${orderId}",
                        "list" to listOf(
                            LogisticsItem("2026-10-10 14:00", "快件已到达【北京朝阳分拨中心】"),
                            LogisticsItem("2026-10-10 10:00", "快件已从【上海总集散中心】发出"),
                            LogisticsItem("2026-10-09 18:00", "快件已揽收")
                        )
                    )
                )
            )
        }

        post("order/info/cancel") {
            @Serializable data class Req(val orderId: Long, val reason: String = "")
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

            suspendTransaction {
                val updated = Orders.update({ Orders.id eq req.orderId }) {
                    it[Orders.status] = 4  // 已取消
                }
                if (updated == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))
                } else {
                    call.respond(ok("取消成功"))
                }
            }
        }

        post("order/info/refund") {
            @Serializable data class Req(val orderId: Long, val reason: String = "")
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

            suspendTransaction {
                val updated = Orders.update({ Orders.id eq req.orderId }) {
                    it[Orders.status] = 5  // 退款中
                }
                if (updated == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))
                } else {
                    call.respond(ok("退款申请已提交"))
                }
            }
        }

        get("order/info/info") {
            val orderId = call.request.queryParameters["id"]?.toLongOrNull()
                ?: return@get call.respond(fail<String>(ResultCode.BAD_REQUEST, "id 参数无效"))

            @Serializable data class OrderGoods(
                val goodsId: Long, val title: String, val mainPic: String,
                val price: Int, val quantity: Int
            )

            suspendTransaction {
                val orderRow = Orders.selectAll().where { Orders.id eq orderId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))

                val statusDesc = listOf("待支付", "已支付", "已发货", "已签收", "已取消", "退款中", "已退款")
                val oid = orderRow[Orders.id]

                val goodsList = OrderItems.selectAll().where { OrderItems.orderId eq oid }
                    .map { item ->
                        OrderGoods(
                            goodsId = item[OrderItems.goodsId],
                            title = item[OrderItems.goodsTitle],
                            mainPic = item[OrderItems.goodsMainPic],
                            price = item[OrderItems.price],
                            quantity = item[OrderItems.quantity]
                        )
                    }

                call.respond(
                    ok(
                        mapOf(
                            "id" to oid,
                            "orderNo" to orderRow[Orders.orderNo],
                            "status" to orderRow[Orders.status],
                            "statusDesc" to statusDesc.getOrElse(orderRow[Orders.status]) { "未知" },
                            "totalPrice" to orderRow[Orders.totalPrice],
                            "remark" to orderRow[Orders.remark],
                            "address" to emptyMap<String, Any>(),  // 暂未解析快照
                            "goodsList" to goodsList,
                            "createTime" to orderRow[Orders.createTime]
                        )
                    )
                )
            }
        }

        get("order/info/confirm") {
            val orderId = call.request.queryParameters["orderId"]?.toLongOrNull()
                ?: return@get call.respond(fail<String>(ResultCode.BAD_REQUEST, "orderId 参数无效"))

            suspendTransaction {
                val updated = Orders.update({ Orders.id eq orderId }) {
                    it[Orders.status] = 3  // 已签收
                    it[Orders.receiveTime] = System.currentTimeMillis().toString()
                }
                if (updated == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))
                } else {
                    call.respond(ok("确认收货成功"))
                }
            }
        }

        post("goods/comment/submit") {
            // 暂无评论表，返回成功占位
            call.respond(ok("评论提交成功"))
        }
    }
}

// ===== 辅助数据类 =====
private data class OrderItemData(
    val goodsId: Long,
    val goodsTitle: String,
    val goodsMainPic: String,
    val specInfo: String?,
    val price: Int,
    val quantity: Int
)
