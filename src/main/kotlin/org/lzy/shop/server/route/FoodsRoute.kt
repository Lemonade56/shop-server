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
import org.lzy.shop.server.common.Img
import org.lzy.shop.server.common.PageResult
import org.lzy.shop.server.common.ResultCode
import org.lzy.shop.server.common.fail
import org.lzy.shop.server.common.ok
import org.lzy.shop.server.config.Comments
import org.lzy.shop.server.config.Goods as GoodsTable
import org.lzy.shop.server.config.GoodsSpecs
import org.lzy.shop.server.config.SearchKeywords
import org.lzy.shop.server.config.Users

/**
 * 食物/搜索相关路由
 *
 * 接口列表：
 *   GET  app/page/foodsDetail           食物详情
 *   POST app/foods/spec/list             食物规格列表（真实 DB）
 *   POST app/foods/comment/page          食物评论分页（真实 DB）
 *   POST app/foods/home/page             首页食品列表分页（全量上架，按 sortNum）
 *   POST app/foods/info/page             搜索食物分页（带筛选）
 *   POST app/foods/search/keyword/list   搜索热门关键词（真实 DB）
 */
fun Route.configureFoodsRoutes() {

    get("page/foodsDetail") {
        val foodsId = call.request.queryParameters["foodsId"]?.toLongOrNull()
            ?: return@get call.respond(fail<String>(ResultCode.BAD_REQUEST, "foodsId 参数无效"))

        @Serializable data class Food(
            val id: Long, val typeId: Long, val title: String, val subTitle: String? = null,
            val mainPic: String, val pics: List<String> = emptyList(), val price: Int,
            val sold: Int = 0, val contentPics: List<String> = emptyList(),
            val recommend: Boolean = false, val featured: Boolean = false,
            val status: Int = 1, val sortNum: Int = 0
        )
        @Serializable data class Detail(
            val foodInfo: Food,
            val coupon: List<String> = emptyList(),
            val comment: List<String> = emptyList()
        )

        suspendTransaction {
            val row = GoodsTable.selectAll().where { GoodsTable.id eq foodsId }.firstOrNull()
                ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "食物不存在"))

            // 解析JSON数组并把每个路径拼完整URL
            fun parseJsonArray(raw: String?): List<String> {
                if (raw.isNullOrBlank()) return emptyList()
                return runCatching {
                    raw.trim().removeSurrounding("[", "]")
                        .split(",")
                        .map { it.trim().removeSurrounding("\"") }
                        .filter { it.isNotBlank() }
                        .map { Img.full(it) ?: it }
                }.getOrDefault(emptyList())
            }

            val foodInfo = Food(
                id = row[GoodsTable.id],
                typeId = row[GoodsTable.typeId],
                title = row[GoodsTable.title],
                subTitle = row[GoodsTable.subTitle],
                mainPic = Img.full(row[GoodsTable.mainPic]) ?: "",
                pics = parseJsonArray(row[GoodsTable.pics]),
                price = row[GoodsTable.price],
                sold = row[GoodsTable.sold],
                contentPics = parseJsonArray(row[GoodsTable.contentPics]),
                recommend = row[GoodsTable.recommend],
                featured = row[GoodsTable.featured],
                status = row[GoodsTable.status],
                sortNum = row[GoodsTable.sortNum]
            )

            call.respond(ok(Detail(foodInfo = foodInfo)))
        }
    }

    post("foods/spec/list") {
        @Serializable data class Req(val foodsId: Long)
        val req = runCatching { call.receive<Req>() }.getOrNull()
            ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

        @Serializable data class SpecItem(val id: Long, val spec: String, val price: Int, val stock: Int)

        suspendTransaction {
            val specs = GoodsSpecs.selectAll().where { GoodsSpecs.goodsId eq req.foodsId }
                .map { row ->
                    SpecItem(
                        id = row[GoodsSpecs.id],
                        spec = row[GoodsSpecs.spec],
                        price = row[GoodsSpecs.price],
                        stock = row[GoodsSpecs.stock]
                    )
                }

            call.respond(ok(specs))
        }
    }

    post("foods/comment/page") {
        @Serializable data class Req(val foodsId: Long = -1L, val page: Int = 1)
        val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

        @Serializable data class CommentItem(
            val id: Long, val userId: Long, val nickname: String,
            val avatarUrl: String, val content: String, val createTime: String
        )

        suspendTransaction {
            val total = Comments.selectAll().where { Comments.goodsId eq req.foodsId }.count()

            // 联查评论 + 用户昵称/头像
            val list = Comments.innerJoin(Users).selectAll().where { Comments.goodsId eq req.foodsId }
                .orderBy(Comments.createTime, SortOrder.DESC)
                .limit(10).offset(((req.page - 1) * 10).toLong())
                .map { row ->
                    CommentItem(
                        id = row[Comments.id],
                        userId = row[Users.id],
                        nickname = (row[Users.nickname] ?: ""),
                        avatarUrl = Img.full((row[Users.avatarUrl] ?: "")) ?: "",
                        content = row[Comments.content],
                        createTime = row[Comments.createTime]
                    )
                }

            call.respond(ok(PageResult(total, list)))
        }
    }

    post("foods/home/page") {
        // 首页食品列表分页 —— 全量上架商品，按 sortNum 排序，每页 10 条
        // 和 foods/info/page （带筛选搜索）区分开，这个给首页下拉加载更多用
        @Serializable data class Req(val page: Int = 1)
        val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

        @Serializable data class MiniFood(
            val id: Long, val typeId: Long, val title: String,
            val mainPic: String, val price: Int, val sold: Int = 0
        )

        suspendTransaction {
            val query = GoodsTable.selectAll().where { GoodsTable.status eq 1 }
                .orderBy(GoodsTable.sortNum, SortOrder.ASC)
            val total = query.count()
            val list = query
                .limit(10).offset(((req.page.coerceAtLeast(1) - 1) * 10).toLong())
                .map { row ->
                    MiniFood(
                        id = row[GoodsTable.id],
                        typeId = row[GoodsTable.typeId],
                        title = row[GoodsTable.title],
                        mainPic = Img.full(row[GoodsTable.mainPic]) ?: "",
                        price = row[GoodsTable.price],
                        sold = row[GoodsTable.sold]
                    )
                }
            call.respond(ok(PageResult(total, list)))
        }
    }

    post("foods/info/page") {
        // 食物列表 / 搜索
        // 参数：keyword 搜索关键词，typeId 分类筛选，sort 排序，minPrice/maxPrice 价格区间，page 页码
        @Serializable data class Req(
            val keyword: String = "", val typeId: Long? = null,
            val sort: String? = null, val minPrice: Int? = null, val maxPrice: Int? = null,
            val page: Int = 1
        )
        val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

        @Serializable data class MiniFood(
            val id: Long, val typeId: Long, val title: String, val subTitle: String? = null,
            val mainPic: String, val price: Int, val sold: Int = 0
        )

        suspendTransaction {
            // 动态组合筛选条件（基础条件：上架食物）
            var whereCond: Op<Boolean> = GoodsTable.status eq 1

            if (req.keyword.isNotBlank()) {
                whereCond = whereCond and (GoodsTable.title like "%${req.keyword}%")
            }
            req.typeId?.let { whereCond = whereCond and (GoodsTable.typeId eq it) }
            req.minPrice?.let { whereCond = whereCond and (GoodsTable.price greater it) }
            req.maxPrice?.let { whereCond = whereCond and (GoodsTable.price less it) }

            val query = GoodsTable.selectAll().where { whereCond }

            // 动态排序：price_asc 价格升序 / price_desc 价格降序 / sold_desc 销量优先 / newest 最新上架
            val ordered = when (req.sort) {
                "price_asc" -> query.orderBy(GoodsTable.price, SortOrder.ASC)
                "price_desc" -> query.orderBy(GoodsTable.price, SortOrder.DESC)
                "sold_desc" -> query.orderBy(GoodsTable.sold, SortOrder.DESC)
                "newest" -> query.orderBy(GoodsTable.id, SortOrder.DESC)
                else -> query.orderBy(GoodsTable.sortNum, SortOrder.ASC)
            }

            val total = ordered.count()

            val list = ordered
                .limit(10).offset(((req.page - 1) * 10).toLong())
                .map { row ->
                    MiniFood(
                        id = row[GoodsTable.id],
                        typeId = row[GoodsTable.typeId],
                        title = row[GoodsTable.title],
                        subTitle = row[GoodsTable.subTitle],
                        mainPic = Img.full(row[GoodsTable.mainPic]) ?: "",
                        price = row[GoodsTable.price],
                        sold = row[GoodsTable.sold]
                    )
                }

            call.respond(ok(PageResult(total, list)))
        }
    }

    post("foods/search/keyword/list") {
        @Serializable data class KeywordItem(val id: Long, val keyword: String)

        suspendTransaction {
            // 从 SearchKeywords 表取热门关键词，不够时用硬编码补
            val fromDb = SearchKeywords.selectAll().where { SearchKeywords.isHot eq true }
                .orderBy(SearchKeywords.sortNum, SortOrder.ASC)
                .map { row ->
                    KeywordItem(
                        id = row[SearchKeywords.id],
                        keyword = row[SearchKeywords.keyword]
                    )
                }

            val fallback = listOf(
                KeywordItem(101, "休闲零食"),
                KeywordItem(102, "进口饼干"),
                KeywordItem(103, "低脂牛奶"),
                KeywordItem(104, "现磨咖啡"),
                KeywordItem(105, "新鲜水果"),
                KeywordItem(106, "有机蔬菜")
            )

            call.respond(ok(if (fromDb.isEmpty()) fallback else fromDb))
        }
    }
}
