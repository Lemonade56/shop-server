package org.lzy.shop.server.route

import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.auth.currentUserId
import org.lzy.shop.server.common.*
import org.lzy.shop.server.config.Addresses
import org.lzy.shop.server.config.Comments
import org.lzy.shop.server.config.Coupons
import org.lzy.shop.server.config.Goods as GoodsTable
import org.lzy.shop.server.config.OrderItems
import org.lzy.shop.server.config.Orders

private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

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

        @Serializable data class DictItem(val id: Int, val name: String)

        val data = when (req?.type) {
            "order_cancel_reason" -> listOf(
                DictItem(1, "不想要了"),
                DictItem(2, "信息填写错误"),
                DictItem(3, "商品缺货"),
                DictItem(4, "其他原因")
            )
            "order_refund_reason" -> listOf(
                DictItem(1, "商品质量问题"),
                DictItem(2, "商品与描述不符"),
                DictItem(3, "发错货"),
                DictItem(4, "其他")
            )
            else -> emptyList<DictItem>()
        }
        call.respond(ok(data))
    }

    authenticate("auth-jwt") {

        // ===== 创建订单 =====
        //
        // 前端可从两个入口调用，请求体结构完全一致：
        //   ① 购物车结算：goodsList 来自购物车，from="cart"
        //   ② 直接购买：goodsList 只含 1 个商品，from="product"
        //
        // 请求 JSON（扁平化，无外层包装）：
        // {
        //   "addressId": 1,
        //   "goodsList": [
        //     { "goodsId": 1, "quantity": 2, "specId": null,
        //       "title": "小米15 Pro", "price": 499900, "mainPic": "https://..." }
        //   ],
        //   "couponId": null,
        //   "remark": "尽快发货",
        //   "from": "cart"
        // }
        //
        // 说明：goodsList 里的 title/price/mainPic 是前端传来用于自身 UI 展示的，
        //       后端做价格计算时**一律从 DB 查询**，不信任前端价格，防止篡改。

        post("order/info/create") {
            @Serializable
            data class SelectedGoods(
                val goodsId: Long,
                val quantity: Int,
                val specId: Long? = null,
                val title: String? = null,          // 前端传来的商品标题（仅前端展示用）
                val price: Int? = null,             // 前端传来的单价（仅前端展示用，后端重新查 DB）
                val mainPic: String? = null         // 前端传来的主图（仅前端展示用）
            )

            @Serializable
            data class CreateOrderReq(
                val addressId: Long,
                val goodsList: List<SelectedGoods>,
                val couponId: Long? = null,
                val remark: String = "",
                val from: String = "product"        // "cart" 或 "product"
            )

            val req = runCatching { call.receive<CreateOrderReq>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            // 参数基本校验
            if (req.addressId <= 0) {
                return@post call.respond(fail<String>(message = "请选择收货地址"))
            }
            if (req.goodsList.isEmpty()) {
                return@post call.respond(fail<String>(message = "商品列表不能为空"))
            }

            val userId = call.currentUserId()
            val orderNo = "SN${System.currentTimeMillis()}"
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                // 1️⃣ 查询地址，构建快照 JSON
                val addressRow = Addresses.selectAll().where {
                    (Addresses.id eq req.addressId) and (Addresses.userId eq userId)
                }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(message = "收货地址不存在"))

                val addressSnapshotMap = mapOf(
                    "receiver" to addressRow[Addresses.receiver],
                    "phone" to addressRow[Addresses.phone],
                    "province" to addressRow[Addresses.province],
                    "city" to addressRow[Addresses.city],
                    "district" to addressRow[Addresses.district],
                    "detail" to addressRow[Addresses.detail]
                )
                val addressSnapshot = json.encodeToString(addressSnapshotMap)

                // 2️⃣ 遍历商品，**从 DB 读取真实价格**（不信任前端传来的 price）
                var totalPrice = 0
                val items = mutableListOf<OrderItemData>()

                req.goodsList.forEach { sg ->
                    val g = GoodsTable.selectAll().where { GoodsTable.id eq sg.goodsId }.firstOrNull()
                        ?: return@suspendTransaction call.respond(fail<String>(
                            ResultCode.BAD_REQUEST, "商品不存在 #${sg.goodsId}"))

                    val dbPrice = g[GoodsTable.price]
                    totalPrice += dbPrice * sg.quantity
                    items.add(
                        OrderItemData(
                            goodsId = sg.goodsId,
                            goodsTitle = g[GoodsTable.title],
                            goodsMainPic = g[GoodsTable.mainPic],
                            specInfo = sg.specId?.toString(),
                            price = dbPrice,
                            quantity = sg.quantity
                        )
                    )
                }

                //计算优惠券减免（如果传了 couponId 则查数据库验证）
                var payPrice = totalPrice
                if (req.couponId != null) {
                    val coupon = Coupons.selectAll().where { Coupons.id eq req.couponId }.firstOrNull()
                    if (coupon != null && totalPrice >= coupon[Coupons.minAmount]) {
                        payPrice -= coupon[Coupons.discount]
                        if (payPrice < 0) payPrice = 0
                    }
                }

                //创建订单
                val orderId = Orders.insert {
                    it[Orders.orderNo] = orderNo
                    it[Orders.userId] = userId
                    it[Orders.addressSnapshot] = addressSnapshot
                    it[Orders.totalPrice] = totalPrice
                    it[Orders.payPrice] = payPrice
                    it[Orders.couponId] = req.couponId
                    it[Orders.status] = 0  // 待支付
                    it[Orders.remark] = req.remark
                    it[Orders.createTime] = now
                } get Orders.id

                //创建订单商品项
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
                        CreateOrderResult(
                            orderNo = orderNo,
                            orderId = orderId,
                            payPrice = payPrice
                        )
                    )
                )
            }
        }

        post("order/info/page") {
            @Serializable data class Req(val page: Int = 1, val status: List<Int>? = null)
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
                    .limit(10).offset(((req.page - 1) * 10).toLong())
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

                call.respond(ok(PageResult(total, list)))
            }
        }

        // ===== 确认订单页数据 =====
        //
        // 前端进入确认订单页时调用此接口，获取：
        //   - 用户所有收货地址（含默认地址标记）
        //   - 可用优惠券列表（status=1 且 remainCount>0）
        //
        // 商品列表由前端自行从购物车或商品详情页组装好后，
        // 连同地址/优惠券一起提交给 order/info/create 接口。

        get("page/confirmOrder") {
            val userId = call.currentUserId()

            @Serializable data class AddressItem(
                val id: Long, val receiver: String, val phone: String,
                val province: String, val city: String, val district: String,
                val detail: String, val isDefault: Boolean
            )
            @Serializable data class CouponItem(
                val id: Long, val name: String, val discount: Int,
                val minAmount: Int, val endTime: String
            )
            @Serializable data class ConfirmOrderData(
                val addressList: List<AddressItem>,
                val couponList: List<CouponItem>
            )

            suspendTransaction {
                val addresses = Addresses.selectAll().where { Addresses.userId eq userId }
                    .orderBy(Addresses.isDefault, SortOrder.DESC)
                    .map { row ->
                        AddressItem(
                            id = row[Addresses.id],
                            receiver = row[Addresses.receiver],
                            phone = row[Addresses.phone],
                            province = row[Addresses.province],
                            city = row[Addresses.city],
                            district = row[Addresses.district],
                            detail = row[Addresses.detail],
                            isDefault = row[Addresses.isDefault]
                        )
                    }

                val coupons = Coupons.selectAll().where {
                    (Coupons.status eq 1) and (Coupons.remainCount greater 0)
                }.map { c ->
                    CouponItem(
                        id = c[Coupons.id],
                        name = c[Coupons.name],
                        discount = c[Coupons.discount],
                        minAmount = c[Coupons.minAmount],
                        endTime = c[Coupons.endTime]
                    )
                }

                call.respond(ok(ConfirmOrderData(addresses, coupons)))
            }
        }

        post("order/pay/alipayAppPay") {
            @Serializable data class Req(val orderId: Long, val payType: String = "alipay")
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            suspendTransaction {
                val order = Orders.selectAll().where { Orders.id eq req.orderId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))

                if (order[Orders.status] != 0) {
                    return@suspendTransaction call.respond(fail<String>(ResultCode.BUSINESS_ERROR, "订单状态不允许支付"))
                }

                // 标记订单为已支付
                Orders.update({ Orders.id eq req.orderId }) {
                    it[Orders.status] = 1
                    it[Orders.payTime] = System.currentTimeMillis().toString()
                }

                val payInfo = "app_id=DEMO&biz_content=orderId=${req.orderId}&sign=DEMO_SIGN"
                call.respond(ok(payInfo))
            }
        }

        get("order/info/logistics") {
            val orderId = call.request.queryParameters["orderId"]?.toLongOrNull()
            @Serializable data class LogisticsItem(val time: String, val content: String)
            @Serializable data class LogisticsInfo(
                val company: String, val companyCode: String,
                val trackingNo: String, val list: List<LogisticsItem>
            )

            call.respond(
                ok(
                    LogisticsInfo(
                        company = "顺丰速运",
                        companyCode = "SF",
                        trackingNo = "SF1234567890${orderId}",
                        list = listOf(
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
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            suspendTransaction {
                val order = Orders.selectAll().where { Orders.id eq req.orderId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))

                // 只有待支付(0)或已支付(1)才能取消
                if (order[Orders.status] !in listOf(0, 1)) {
                    return@suspendTransaction call.respond(fail<String>(ResultCode.BUSINESS_ERROR, "当前订单状态不允许取消"))
                }

                Orders.update({ Orders.id eq req.orderId }) {
                    it[Orders.status] = 4  // 已取消
                }
                call.respond(okMsg("取消订单"))
            }
        }

        post("order/info/refund") {
            @Serializable data class Req(val orderId: Long, val reason: String = "")
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            suspendTransaction {
                val order = Orders.selectAll().where { Orders.id eq req.orderId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "订单不存在"))

                // 只有已支付(1)/已发货(2)/已签收(3)才能申请退款
                // 退款中(5)/已退款(6)/已取消(4)/待支付(0) 都不能申请
                if (order[Orders.status] !in listOf(1, 2, 3)) {
                    return@suspendTransaction call.respond(fail<String>(ResultCode.BUSINESS_ERROR, "当前订单状态不允许退款"))
                }

                Orders.update({ Orders.id eq req.orderId }) {
                    it[Orders.status] = 5  // 退款中
                }
                call.respond(okMsg("提交退款申请"))
            }
        }

        get("order/info/info") {
            val orderId = call.request.queryParameters["id"]?.toLongOrNull()
                ?: return@get call.respond(fail<String>(ResultCode.BAD_REQUEST, "id 参数无效"))

            @Serializable data class OrderGoods(
                val goodsId: Long, val title: String, val mainPic: String,
                val price: Int, val quantity: Int
            )
            @Serializable data class OrderDetail(
                val id: Long, val orderNo: String, val status: Int, val statusDesc: String,
                val totalPrice: Int, val payPrice: Int, val remark: String,
                val address: String,  // 地址快照 JSON 字符串
                val goodsList: List<OrderGoods>, val createTime: String
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
                        OrderDetail(
                            id = oid,
                            orderNo = orderRow[Orders.orderNo],
                            status = orderRow[Orders.status],
                            statusDesc = statusDesc.getOrElse(orderRow[Orders.status]) { "未知" },
                            totalPrice = orderRow[Orders.totalPrice],
                            payPrice = orderRow[Orders.payPrice],
                            remark = orderRow[Orders.remark],
                            address = orderRow[Orders.addressSnapshot],
                            goodsList = goodsList,
                            createTime = orderRow[Orders.createTime]
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
                    call.respond(okMsg("确认收货"))
                }
            }
        }

        post("goods/comment/submit") {
            @Serializable data class Req(
                val goodsId: Long, val content: String,
                val images: List<String> = emptyList(), val orderId: Long? = null
            )
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            if (req.content.isBlank()) {
                return@post call.respond(fail<String>(message = "评论内容不能为空"))
            }

            val userId = call.currentUserId()
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                Comments.insert {
                    it[Comments.goodsId] = req.goodsId
                    it[Comments.userId] = userId
                    it[Comments.orderId] = req.orderId
                    it[Comments.content] = req.content
                    it[Comments.images] = if (req.images.isNotEmpty()) "[${req.images.joinToString(",") { "\"$it\"" }}]" else null
                    it[Comments.createTime] = now
                }
                call.respond(okMsg("提交评论"))
            }
        }
    }
}

// ===== 辅助数据类 =====
@Serializable
private data class CreateOrderResult(
    val orderNo: String,
    val orderId: Long,
    val payPrice: Int
)

private data class OrderItemData(
    val goodsId: Long,
    val goodsTitle: String,
    val goodsMainPic: String,
    val specInfo: String?,
    val price: Int,
    val quantity: Int
)
