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
import org.lzy.shop.server.config.Collections
import org.lzy.shop.server.config.Goods as GoodsTable
import org.lzy.shop.server.config.RecipeCollections
import org.lzy.shop.server.config.Recipes

/**
 * 收藏路由 —— 商品收藏 + 菜谱收藏
 */
fun Route.configureCollectionRoutes() {

    authenticate("auth-jwt") {

        // ===== 商品收藏（保留原有）=====

        post("market/collect/page") {
            @Serializable data class Req(val page: Int = 1)
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
                    .limit(10).offset(((req.page - 1) * 10).toLong())
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
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                val goods = GoodsTable.selectAll().where { GoodsTable.id eq req.goodsId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "商品不存在"))

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

                call.respond(okMsg("收藏商品"))
            }
        }

        post("market/collect/delete") {
            @Serializable data class Req(val goodsId: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                Collections.deleteWhere {
                    (Collections.userId eq userId) and (Collections.goodsId eq req.goodsId)
                }
                call.respond(okMsg("取消收藏"))
            }
        }

        post("market/collect/isCollect") {
            @Serializable data class Req(val goodsId: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                val collected = Collections.selectAll().where {
                    (Collections.userId eq userId) and (Collections.goodsId eq req.goodsId)
                }.firstOrNull() != null

                call.respond(ok(collected))
            }
        }

        // ===== 菜谱收藏（新增）=====

        post("recipe/collect/page") {
            @Serializable data class Req(val page: Int = 1)
            val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

            @Serializable data class RecipeCollectItem(
                val id: Long, val recipeId: Long, val name: String,
                val pic: String, val time: Int, val difficulty: Int,
                val collectTime: String
            )

            val userId = call.currentUserId()

            suspendTransaction {
                val total = RecipeCollections.selectAll().where { RecipeCollections.userId eq userId }.count()

                val list = RecipeCollections.innerJoin(Recipes)
                    .selectAll().where { RecipeCollections.userId eq userId }
                    .orderBy(RecipeCollections.createTime, SortOrder.DESC)
                    .limit(10).offset(((req.page - 1) * 10).toLong())
                    .map { row ->
                        RecipeCollectItem(
                            id = row[RecipeCollections.id],
                            recipeId = row[Recipes.id],
                            name = row[Recipes.name],
                            pic = row[Recipes.pic],
                            time = row[Recipes.time],
                            difficulty = row[Recipes.difficulty],
                            collectTime = row[RecipeCollections.createTime]
                        )
                    }

                call.respond(ok(PageResult(total, list)))
            }
        }

        post("recipe/collect/add") {
            @Serializable data class Req(val recipeId: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                val recipe = Recipes.selectAll().where { Recipes.id eq req.recipeId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "菜谱不存在"))

                val existed = RecipeCollections.selectAll().where {
                    (RecipeCollections.userId eq userId) and (RecipeCollections.recipeId eq req.recipeId)
                }.firstOrNull()
                if (existed != null) {
                    return@suspendTransaction call.respond(fail<String>(ResultCode.BUSINESS_ERROR, "已收藏过"))
                }

                RecipeCollections.insert {
                    it[RecipeCollections.userId] = userId
                    it[RecipeCollections.recipeId] = req.recipeId
                    it[RecipeCollections.createTime] = now
                }

                call.respond(okMsg("收藏菜谱"))
            }
        }

        post("recipe/collect/delete") {
            @Serializable data class Req(val recipeId: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                RecipeCollections.deleteWhere {
                    (RecipeCollections.userId eq userId) and (RecipeCollections.recipeId eq req.recipeId)
                }
                call.respond(okMsg("取消收藏"))
            }
        }

        post("recipe/collect/isCollect") {
            @Serializable data class Req(val recipeId: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                val collected = RecipeCollections.selectAll().where {
                    (RecipeCollections.userId eq userId) and (RecipeCollections.recipeId eq req.recipeId)
                }.firstOrNull() != null

                call.respond(ok(collected))
            }
        }
    }
}
