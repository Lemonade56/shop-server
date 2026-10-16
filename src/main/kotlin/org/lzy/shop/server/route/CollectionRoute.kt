package org.lzy.shop.server.route

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.auth.currentUserId
import org.lzy.shop.server.common.*
import org.lzy.shop.server.config.Collections
import org.lzy.shop.server.config.Goods as GoodsTable

/**
 * 商品收藏路由 —— 对齐 App 端 CollectionApi.kt
 */
fun Route.configureCollectionRoutes() {

    authenticate("auth-jwt") {

        post("market/collect/page") {
            @Serializable data class Req(val page: Int = 1, val size: Int = 20)
            val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

            @Serializable data class CollectItem(
                val id: Long, val goodsId: Long, val title: String,
                val mainPic: String, val price: Int, val collectTime: String
            )

            val userId = call.currentUserId()

            suspendTransaction {
                val total = Collections.selectAll().where { Collections.userId eq userId }.count()

                val list = Collections.innerJoin(GoodsTable)
                    .selectAll().where { Collections.userId eq userId }
                    .orderBy(Collections.createTime, SortOrder.DESC)
                    .limit(req.size.coerceAtLeast(1))
                    .map { row ->
                        CollectItem(
                            id = row[Collections.id],
                            goodsId = row[GoodsTable.id],
                            title = row[GoodsTable.title],
                            mainPic = row[GoodsTable.mainPic],
                            price = row[GoodsTable.price],
                            collectTime = row[Collections.createTime]
                        )
                    }

                call.respond(ok(PageResult(total, list)))
            }
        }

        post("market/collect/add") {
            @Serializable data class Req(val goodsId: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

            val userId = call.currentUserId()
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                // 检查商品是否存在
                val goods = GoodsTable.selectAll().where { GoodsTable.id eq req.goodsId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "商品不存在"))

                // 检查是否已收藏
                val existed = Collections.selectAll().where {
                    (Collections.userId eq userId) and (Collections.goodsId eq req.goodsId)
                }.firstOrNull()
                if (existed != null) {
                    return@suspendTransaction call.respond(fail<String>(ResultCode.BUSINESS_ERROR, "已收藏过"))
                }

                Collections.insert {
                    it[Collections.userId] = userId
                    it[Collections.goodsId] = req.goodsId
                    it[Collections.createTime] = now
                }

                call.respond(ok("收藏成功"))
            }
        }

        post("market/collect/delete") {
            @Serializable data class Req(val goodsId: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                Collections.deleteWhere {
                    (Collections.userId eq userId) and (Collections.goodsId eq req.goodsId)
                }
                call.respond(ok("取消成功"))
            }
        }

        post("market/collect/isCollect") {
            @Serializable data class Req(val goodsId: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                val collected = Collections.selectAll().where {
                    (Collections.userId eq userId) and (Collections.goodsId eq req.goodsId)
                }.firstOrNull() != null

                call.respond(ok(collected))
            }
        }
    }
}
