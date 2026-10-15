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
import org.lzy.shop.server.common.ResultCode
import org.lzy.shop.server.common.fail
import org.lzy.shop.server.common.ok
import org.lzy.shop.server.config.Notifications

/**
 * 消息通知路由 —— 对齐 App 端 NotificationApi.kt
 *
 * 业务场景：订单状态变更、退款结果、优惠券过期提醒、版本公告等
 */
fun Route.configureNotificationRoutes() {

    authenticate("auth-jwt") {

        post("market/notice/page") {
            @Serializable data class Req(val page: Int = 1, val size: Int = 10)
            val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

            @Serializable data class NoticeItem(
                val id: Long, val title: String, val content: String,
                val type: Int, val isRead: Boolean, val createTime: String
            )

            val userId = call.currentUserId()

            suspendTransaction {
                val total = Notifications.selectAll().where { Notifications.userId eq userId }.count()

                val list = Notifications.selectAll().where { Notifications.userId eq userId }
                    .orderBy(Notifications.createTime, SortOrder.DESC)
                    .limit(req.size.coerceAtLeast(1)).offset(((req.page - 1) * req.size.coerceAtLeast(1)).toLong())
                    .map { row ->
                        NoticeItem(
                            id = row[Notifications.id],
                            title = row[Notifications.title],
                            content = row[Notifications.content],
                            type = row[Notifications.type],
                            isRead = row[Notifications.isRead],
                            createTime = row[Notifications.createTime]
                        )
                    }

                call.respond(ok(mapOf("total" to total, "list" to list)))
            }
        }

        post("market/notice/unread") {
            val userId = call.currentUserId()

            suspendTransaction {
                val count = Notifications.selectAll().where {
                    (Notifications.userId eq userId) and (Notifications.isRead eq false)
                }.count()

                call.respond(ok(count))
            }
        }

        post("market/notice/read") {
            @Serializable data class Req(val id: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                Notifications.update({
                    (Notifications.id eq req.id) and (Notifications.userId eq userId)
                }) {
                    it[Notifications.isRead] = true
                }

                call.respond(ok("标记成功"))
            }
        }

        post("market/notice/readAll") {
            val userId = call.currentUserId()

            suspendTransaction {
                Notifications.update({ Notifications.userId eq userId }) {
                    it[Notifications.isRead] = true
                }

                call.respond(ok("全部已读"))
            }
        }
    }
}
