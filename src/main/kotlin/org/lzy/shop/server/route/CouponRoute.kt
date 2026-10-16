package org.lzy.shop.server.route

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.auth.currentUserId
import org.lzy.shop.server.common.*
import org.lzy.shop.server.config.Coupons
import org.lzy.shop.server.config.UserCoupons

/**
 * 优惠券路由 —— 对齐 App 端 CouponApi.kt
 */
fun Route.configureCouponRoutes() {

    post("market/coupon/info/page") {
        // 公共：优惠券可领取列表
        @Serializable data class Coupon(
            val id: Long, val name: String, val discount: Int,
            val minAmount: Int, val totalCount: Int, val remainCount: Int,
            val startTime: String, val endTime: String, val status: Int
        )

        suspendTransaction {
            val list = Coupons.selectAll().where { Coupons.status eq 1 }
                .orderBy(Coupons.id, SortOrder.DESC)
                .map { row ->
                    Coupon(
                        id = row[Coupons.id],
                        name = row[Coupons.name],
                        discount = row[Coupons.discount],
                        minAmount = row[Coupons.minAmount],
                        totalCount = row[Coupons.totalCount],
                        remainCount = row[Coupons.remainCount],
                        startTime = row[Coupons.startTime],
                        endTime = row[Coupons.endTime],
                        status = row[Coupons.status]
                    )
                }

            call.respond(ok(list))
        }
    }

    authenticate("auth-jwt") {

        post("market/coupon/user/page") {
            @Serializable data class Req(val page: Int = 1, val size: Int = 20)
            val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

            @Serializable data class UserCouponItem(
                val id: Long, val couponId: Long, val name: String,
                val discount: Int, val minAmount: Int,
                val startTime: String, val endTime: String, val status: Int
            )

            val userId = call.currentUserId()

            suspendTransaction {
                // 联查 UserCoupons + Coupons
                val total = UserCoupons.innerJoin(Coupons).selectAll().where {
                    UserCoupons.userId eq userId
                }.count()

                val list = UserCoupons.innerJoin(Coupons)
                    .selectAll().where { UserCoupons.userId eq userId }
                    .orderBy(UserCoupons.receiveTime, SortOrder.DESC)
                    .limit(req.size.coerceAtLeast(1))
                    .map { row ->
                        UserCouponItem(
                            id = row[UserCoupons.id],
                            couponId = row[Coupons.id],
                            name = row[Coupons.name],
                            discount = row[Coupons.discount],
                            minAmount = row[Coupons.minAmount],
                            startTime = row[Coupons.startTime],
                            endTime = row[Coupons.endTime],
                            status = row[UserCoupons.status]
                        )
                    }

                call.respond(ok(PageResult(total, list)))
            }
        }

        post("market/coupon/user/receive") {
            @Serializable data class Req(val couponId: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

            val userId = call.currentUserId()
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                val coupon = Coupons.selectAll().where { Coupons.id eq req.couponId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "优惠券不存在"))

                if (coupon[Coupons.remainCount] <= 0) {
                    return@suspendTransaction call.respond(fail<String>(ResultCode.BUSINESS_ERROR, "优惠券已领完"))
                }

                // 检查是否已领取过
                val existed = UserCoupons.selectAll().where {
                    (UserCoupons.userId eq userId) and (UserCoupons.couponId eq req.couponId)
                }.firstOrNull()
                if (existed != null) {
                    return@suspendTransaction call.respond(fail<String>(ResultCode.BUSINESS_ERROR, "已领取过该优惠券"))
                }

                UserCoupons.insert {
                    it[UserCoupons.userId] = userId
                    it[UserCoupons.couponId] = req.couponId
                    it[UserCoupons.status] = 0
                    it[UserCoupons.receiveTime] = now
                }

                Coupons.update({ Coupons.id eq req.couponId }) {
                    it[Coupons.remainCount] = coupon[Coupons.remainCount] - 1
                }

                call.respond(ok("领取成功"))
            }
        }
    }
}
