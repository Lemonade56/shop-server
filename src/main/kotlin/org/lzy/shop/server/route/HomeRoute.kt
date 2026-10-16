package org.lzy.shop.server.route

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.common.*
import org.lzy.shop.server.config.Banners
import org.lzy.shop.server.config.Categories
import org.lzy.shop.server.config.Goods

/**
 * 首页相关路由 —— 对齐 App 端 HomeApi.kt
 *
 * 接口列表：
 *   GET  app/page/home            首页聚合数据（轮播+分类+推荐+优惠券）
 *   POST app/goods/type/list      商品分类列表
 *   POST app/goods/info/page      首页商品分页（复用 Goods 搜索接口）
 */
fun Route.configureHomeRoutes() {

    get("page/home") {
        @Serializable data class Banner(val id: Long, val pic: String, val url: String = "")
        @Serializable data class Category(val id: Long, val name: String, val pic: String)
        @Serializable data class MiniGoods(
            val id: Long, val typeId: Long, val title: String, val mainPic: String,
            val price: Int, val sold: Int = 0
        )
        @Serializable data class HomeData(
            val banner: List<Banner>,
            val category: List<Category>,
            val categoryAll: List<Category>,
            val flashSale: List<MiniGoods>,
            val recommend: List<MiniGoods>,
            val coupon: List<String>,
            val goods: List<MiniGoods>
        )

        suspendTransaction {
            // 轮播图：从 Banners 表取启用的
            val banners = Banners.selectAll().where { Banners.status eq 1 }
                .orderBy(Banners.sortNum, SortOrder.ASC)
                .map { row ->
                    Banner(
                        id = row[Banners.id],
                        pic = row[Banners.pic],
                        url = row[Banners.url] ?: ""
                    )
                }

            // 分类：从 Categories 表取顶级分类
            val categoryList = Categories.selectAll().where { Categories.parentId eq 0 }
                .orderBy(Categories.sortNum, SortOrder.ASC)
                .map { row ->
                    Category(
                        id = row[Categories.id],
                        name = row[Categories.name],
                        pic = row[Categories.pic] ?: ""
                    )
                }

            // 限时精选：取 featured 商品前 2 个
            val flashSale = Goods.selectAll().where { (Goods.featured eq true) and (Goods.status eq 1) }
                .limit(2)
                .map { row ->
                    MiniGoods(
                        id = row[Goods.id],
                        typeId = row[Goods.typeId],
                        title = row[Goods.title],
                        mainPic = row[Goods.mainPic],
                        price = row[Goods.price],
                        sold = row[Goods.sold]
                    )
                }

            // 推荐商品：取 recommend 商品前 4 个
            val recommend = Goods.selectAll().where { (Goods.recommend eq true) and (Goods.status eq 1) }
                .limit(4)
                .map { row ->
                    MiniGoods(
                        id = row[Goods.id],
                        typeId = row[Goods.typeId],
                        title = row[Goods.title],
                        mainPic = row[Goods.mainPic],
                        price = row[Goods.price],
                        sold = row[Goods.sold]
                    )
                }

            call.respond(
                ok(
                    HomeData(
                        banner = banners,
                        category = categoryList,
                        categoryAll = categoryList,
                        flashSale = flashSale,
                        recommend = recommend,
                        coupon = emptyList(),
                        goods = recommend
                    )
                )
            )
        }
    }

    post("goods/type/list") {
        // 分类列表：返回树形结构
        @Serializable data class CategoryNode(
            val id: Long, val name: String, val parentId: Long = 0,
            val pic: String = "", val sortNum: Int = 0, val children: List<CategoryNode> = emptyList()
        )

        suspendTransaction {
            val all = Categories.selectAll().orderBy(Categories.sortNum, SortOrder.ASC).toList()
            val roots = all.filter { it[Categories.parentId] == 0L }

            fun buildChildren(parentId: Long): List<CategoryNode> {
                return all.filter { it[Categories.parentId] == parentId }
                    .map { row ->
                        CategoryNode(
                            id = row[Categories.id],
                            name = row[Categories.name],
                            parentId = row[Categories.parentId],
                            pic = row[Categories.pic] ?: "",
                            sortNum = row[Categories.sortNum],
                            children = buildChildren(row[Categories.id])
                        )
                    }
            }

            val tree = roots.map { row ->
                CategoryNode(
                    id = row[Categories.id],
                    name = row[Categories.name],
                    parentId = row[Categories.parentId],
                    pic = row[Categories.pic] ?: "",
                    sortNum = row[Categories.sortNum],
                    children = buildChildren(row[Categories.id])
                )
            }

            call.respond(ok(tree))
        }
    }

    post("goods/info/page") {
        // 首页"全部商品"分页 —— 和搜索共用同一个接口
        @Serializable data class Req(val page: Int = 1, val size: Int = 10)
        val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

        @Serializable data class MiniGoods(
            val id: Long, val typeId: Long, val title: String, val mainPic: String,
            val price: Int, val sold: Int = 0
        )

        suspendTransaction {
            val total = Goods.selectAll().where { Goods.status eq 1 }.count()
            val list = Goods.selectAll().where { Goods.status eq 1 }
                .orderBy(Goods.sortNum, SortOrder.ASC)
                .limit(req.size.coerceAtLeast(1))
                .map { row ->
                    MiniGoods(
                        id = row[Goods.id],
                        typeId = row[Goods.typeId],
                        title = row[Goods.title],
                        mainPic = row[Goods.mainPic],
                        price = row[Goods.price],
                        sold = row[Goods.sold]
                    )
                }

            call.respond(ok(PageResult(total, list)))
        }
    }
}
