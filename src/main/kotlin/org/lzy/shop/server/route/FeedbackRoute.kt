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
import org.lzy.shop.server.config.Feedbacks

/**
 * 用户反馈路由 —— 对齐 App 端 FeedbackApi.kt
 */
fun Route.configureFeedbackRoutes() {

    authenticate("auth-jwt") {

        post("app/feedback/page") {
            @Serializable data class Req(val page: Int = 1, val size: Int = 10)
            val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

            @Serializable data class FeedBackItem(
                val id: Long, val type: String, val content: String,
                val images: List<String> = emptyList(),
                val reply: String? = null, val createTime: String
            )

            val userId = call.currentUserId()

            suspendTransaction {
                val total = Feedbacks.selectAll().where { Feedbacks.userId eq userId }.count()

                val list = Feedbacks.selectAll().where { Feedbacks.userId eq userId }
                    .orderBy(Feedbacks.createTime, SortOrder.DESC)
                    .limit(req.size.coerceAtLeast(1))
                    .map { row ->
                        // images 字段是 JSON 字符串，简单解析
                        val rawImages = row[Feedbacks.images]
                        val images: List<String> = if (!rawImages.isNullOrBlank()) {
                            runCatching {
                                rawImages.trim().removeSurrounding("[", "]")
                                    .split(",")
                                    .map { it.trim().removeSurrounding("\"") }
                                    .filter { it.isNotBlank() }
                            }.getOrDefault(emptyList())
                        } else emptyList()

                        FeedBackItem(
                            id = row[Feedbacks.id],
                            type = row[Feedbacks.type],
                            content = row[Feedbacks.content],
                            images = images,
                            reply = row[Feedbacks.reply],
                            createTime = row[Feedbacks.createTime]
                        )
                    }

                call.respond(ok(PageResult(total, list)))
            }
        }

        post("app/feedback/submit") {
            @Serializable data class Req(
                val type: String, val content: String,
                val images: List<String> = emptyList()
            )
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

            val userId = call.currentUserId()
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                Feedbacks.insert {
                    it[Feedbacks.userId] = userId
                    it[Feedbacks.type] = req.type
                    it[Feedbacks.content] = req.content
                    it[Feedbacks.images] = if (req.images.isNotEmpty()) "[${req.images.joinToString(",") { "\"$it\"" }}]" else null
                    it[Feedbacks.createTime] = now
                }

                call.respond(ok("反馈提交成功，感谢您的意见！"))
            }
        }
    }
}
