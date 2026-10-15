package org.lzy.shop.server.route

import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.lzy.shop.server.common.ok

/**
 * 客服消息路由 —— 对齐 App 端 CustomerServiceApi.kt
 *
 * 完整实现需要 WebSocket 做实时聊天，这里先做 HTTP 轮询版骨架。
 */
fun Route.configureCustomerServiceRoutes() {

    authenticate("auth-jwt") {

        post("cs/session/create") {
            // 创建客服会话，返回 sessionId
            call.respond(
                ok(
                    mapOf(
                        "sessionId" to "CS${System.currentTimeMillis()}",
                        "createTime" to System.currentTimeMillis().toString()
                    )
                )
            )
        }

        post("cs/msg/page") {
            @Serializable data class Req(val sessionId: String = "", val page: Int = 1, val size: Int = 20)
            val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

            @Serializable data class Msg(
                val id: Long, val sender: String,  // "user" or "cs"
                val content: String, val type: Int = 0, val time: String
            )

            call.respond(
                ok(
                    mapOf(
                        "total" to 3,
                        "list" to listOf(
                            Msg(1, "user", "你好，我想咨询一下...", 0, "2026-10-10 10:00"),
                            Msg(2, "cs", "您好，很高兴为您服务！", 0, "2026-10-10 10:00"),
                            Msg(3, "cs", "请问有什么可以帮您？", 0, "2026-10-10 10:01")
                        )
                    )
                )
            )
        }

        post("cs/msg/send") {
            @Serializable data class Req(val sessionId: String, val content: String)
            val req = runCatching { call.receive<Req>() }.getOrNull()

            // Demo：客服自动回复
            call.respond(ok("消息已发送"))
        }
    }
}
