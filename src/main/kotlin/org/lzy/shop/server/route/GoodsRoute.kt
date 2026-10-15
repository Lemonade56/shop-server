package org.lzy.shop.server.route

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.common.ok
import org.lzy.shop.server.common.fail
import org.lzy.shop.server.common.ResultCode
import org.lzy.shop.server.config.Comments
import org.lzy.shop.server.config.Goods as GoodsTable
import org.lzy.shop.server.config.GoodsSpecs
import org.lzy.shop.server.config.SearchKeywords
import org.lzy.shop.server.config.Users

/**
 * 商品/搜索相关路由 —— 对齐 App 端 GoodsApi.kt
 *
 * 接口列表：
 *   GET  app/page/goodsDetail         商品详情
 *   POST app/goods/spec/list          商品规格列表（真实 DB）
 *   POST app/goods/comment/page       商品评论分页（真实 DB）
 *   POST app/goods/info/page          搜索商品分页
 *   POST app/goods/search/keyword/list  搜索热门关键词（真实 DB）
 */
fun Route.configureGoodsRoutes() {

    get("page/goodsDetail") {
        val goodsId = call.request.queryParameters["goodsId"]?.toLongOrNull()
            ?: return@get call.respond(fail<Any>(ResultCode.BAD_REQUEST, "goodsId 参数无效"))

        @Serializable data class Goods(
            val id: Long, val typeId: Long, val title: String, val subTitle: String? = null,
            val mainPic: String, val pics: List<String> = emptyList(), val price: Int,
            val sold: Int = 0, val contentPics: List<String> = emptyList(),
            val recommend: Boolean = false, val featured: Boolean = false,
            val status: Int = 1, val sortNum: Int = 0
        )
        @Serializable data class Detail(
            val goodsInfo: Goods,
            val coupon: List<String> = emptyList(),
            val comment: List<String> = emptyList()
        )

        suspendTransaction {
            val row = GoodsTable.selectAll().where { GoodsTable.id eq goodsId }.firstOrNull()
                ?: return@suspendTransaction call.respond(fail<Any>(ResultCode.NOT_FOUND, "商品不存在"))

            fun parseJsonArray(raw: String?): List<String> {
                if (raw.isNullOrBlank()) return emptyList()
                return runCatching {
                    raw.trim().removeSurrounding("[", "]")
                        .split(",")
                        .map { it.trim().removeSurrounding("\"") }
                        .filter { it.isNotBlank() }
                }.getOrDefault(emptyList())
            }

            val goodsInfo = Goods(
                id = row[GoodsTable.id],
                typeId = row[GoodsTable.typeId],
                title = row[GoodsTable.title],
                subTitle = row[GoodsTable.subTitle],
                mainPic = row[GoodsTable.mainPic],
                pics = parseJsonArray(row[GoodsTable.pics]),
                price = row[GoodsTable.price],
                sold = row[GoodsTable.sold],
                contentPics = parseJsonArray(row[GoodsTable.contentPics]),
                recommend = row[GoodsTable.recommend],
                featured = row[GoodsTable.featured],
                status = row[GoodsTable.status],
                sortNum = row[GoodsTable.sortNum]
            )

            call.respond(ok(Detail(goodsInfo = goodsInfo)))
        }
    }

    post("goods/spec/list") {
        @Serializable data class Req(val goodsId: Long)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))

        suspendTransaction {
            val specs = GoodsSpecs.selectAll().where { GoodsSpecs.goodsId eq req.goodsId }
                .map { row ->
                    mapOf(
                        "id" to row[GoodsSpecs.id],
                        "spec" to row[GoodsSpecs.spec],
                        "price" to row[GoodsSpecs.price],
                        "stock" to row[GoodsSpecs.stock]
                    )
                }

            call.respond(ok(specs))
        }
    }

    post("goods/comment/page") {
        @Serializable data class Req(val goodsId: Long = -1L, val page: Int = 1, val size: Int = 10)
        val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

        suspendTransaction {
            val total = Comments.selectAll().where { Comments.goodsId eq req.goodsId }.count()

            // 联查评论 + 用户昵称/头像
            val list = Comments.innerJoin(Users).selectAll().where { Comments.goodsId eq req.goodsId }
                .orderBy(Comments.createTime, SortOrder.DESC)
                .limit(req.size.coerceAtLeast(1)).offset(((req.page - 1) * req.size.coerceAtLeast(1)).toLong())
                .map { row ->
                    mapOf(
                        "id" to row[Comments.id],
                        "userId" to row[Users.id],
                        "nickname" to (row[Users.nickname] ?: ""),
                        "avatarUrl" to (row[Users.avatarUrl] ?: ""),
                        "content" to row[Comments.content],
                        "createTime" to row[Comments.createTime]
                    )
                }

            call.respond(ok(mapOf("total" to total, "list" to list)))
        }
    }

    post("goods/info/page") {
        // 搜索 / 商品列表
        @Serializable data class Req(
            val keyWord: String = "", val page: Int = 1, val size: Int = 10,
            val typeId: List<Int>? = null, val order: String? = null,
            val sort: String? = null, val featured: Boolean? = null,
            val minPrice: String? = null, val maxPrice: String? = null
        )
        val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

        @Serializable data class MiniGoods(
            val id: Long, val typeId: Long, val title: String, val subTitle: String? = null,
            val mainPic: String, val price: Int, val sold: Int = 0
        )

        suspendTransaction {
            val query = if (req.keyWord.isNotBlank()) {
                GoodsTable.selectAll().where {
                    (GoodsTable.status eq 1) and
                        (GoodsTable.title like "%${req.keyWord}%")
                }
            } else {
                GoodsTable.selectAll().where { GoodsTable.status eq 1 }
            }

            val total = query.count()

            val list = query
                .orderBy(GoodsTable.sortNum, SortOrder.ASC)
                .limit(req.size.coerceAtLeast(1))
                .map { row ->
                    MiniGoods(
                        id = row[GoodsTable.id],
                        typeId = row[GoodsTable.typeId],
                        title = row[GoodsTable.title],
                        subTitle = row[GoodsTable.subTitle],
                        mainPic = row[GoodsTable.mainPic],
                        price = row[GoodsTable.price],
                        sold = row[GoodsTable.sold]
                    )
                }

            call.respond(ok(mapOf("total" to total, "list" to list)))
        }
    }

    post("goods/search/keyword/list") {
        suspendTransaction {
            // 从 SearchKeywords 表取热门关键词，不够时用硬编码补
            val fromDb = SearchKeywords.selectAll().where { SearchKeywords.isHot eq true }
                .orderBy(SearchKeywords.sortNum, SortOrder.ASC)
                .map { row ->
                    mapOf(
                        "id" to row[SearchKeywords.id],
                        "keyword" to row[SearchKeywords.keyword]
                    )
                }

            val fallback = listOf(
                mapOf("id" to 101, "keyword" to "iPhone 16"),
                mapOf("id" to 102, "keyword" to "小米15"),
                mapOf("id" to 103, "keyword" to "羽绒服"),
                mapOf("id" to 104, "keyword" to "戴森"),
                mapOf("id" to 105, "keyword" to "AirPods"),
                mapOf("id" to 106, "keyword" to "Switch")
            )

            call.respond(ok(if (fromDb.isEmpty()) fallback else fromDb))
        }
    }
}
