package org.lzy.shop.server.route

import io.ktor.server.application.call
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
import org.lzy.shop.server.config.Goods
import org.lzy.shop.server.config.Recipes

/**
 * 首页相关路由 —— 对齐 App 端首页设计
 *
 * 接口列表：
 *   GET  app/page/home            首页聚合数据（轮播+用餐场景+热门菜谱+推荐食品）
 *   POST app/foods/type/list      食物分类列表（原分类接口保留，其他模块可能用到）
 *
 * 首页结构（对应App截图）：
 *   - 搜索栏（前端自行实现）
 *   - Banner轮播（今日吃得好一点）
 *   - 用餐场景分类：早餐/午餐/晚餐/零食补给（4个固定入口）
 *   - 本周热门菜谱（3张菜谱卡片）
 *   - 推荐食品（横向滚动）
 */
fun Route.configureHomeRoutes() {

    get("page/home") {
        @Serializable data class Banner(val id: Long, val pic: String, val url: String = "", val title: String = "")
        @Serializable data class MealScene(val id: Long, val name: String, val pic: String)
        @Serializable data class HotRecipe(
            val id: Long, val name: String, val pic: String,
            val time: Int, val difficulty: Int
        )
        @Serializable data class MiniFood(
            val id: Long, val typeId: Long, val title: String,
            val mainPic: String, val price: Int
        )
        @Serializable data class HomeData(
            val banner: List<Banner>,
            val mealScenes: List<MealScene>,
            val hotRecipes: List<HotRecipe>,
            val recommendFoods: List<MiniFood>
        )

        suspendTransaction {
            // 1. 轮播图
            val banners = Banners.selectAll().where { Banners.status eq 1 }
                .orderBy(Banners.sortNum, SortOrder.ASC)
                .map { row ->
                    Banner(
                        id = row[Banners.id],
                        pic = Img.full(call, row[Banners.pic]) ?: "",
                        url = row[Banners.url] ?: "",
                        title = row[Banners.title] ?: ""
                    )
                }

            // 2. 用餐场景分类 —— 4个固定入口（与商品分类Categories表无关）
            val mealScenes = listOf(
                MealScene(1, "早餐", Img.full(call, "/app/images/meal-breakfast.jpg")!!),
                MealScene(2, "午餐", Img.full(call, "/app/images/meal-lunch.jpg")!!),
                MealScene(3, "晚餐", Img.full(call, "/app/images/meal-dinner.jpg")!!),
                MealScene(4, "零食补给", Img.full(call, "/app/images/meal-snack.jpg")!!),
            )

            // 3. 本周热门菜谱 —— 取上架菜谱前 3 张
            val hotRecipes = Recipes.selectAll().where { Recipes.status eq 1 }
                .orderBy(Recipes.sortNum, SortOrder.ASC)
                .limit(3)
                .map { row ->
                    HotRecipe(
                        id = row[Recipes.id],
                        name = row[Recipes.name],
                        pic = Img.full(call, row[Recipes.pic]) ?: "",
                        time = row[Recipes.time],
                        difficulty = row[Recipes.difficulty]
                    )
                }

            // 4. 推荐食品 —— 取 recommend=true 且上架的商品，前 4 个
            val recommendFoods = Goods.selectAll().where { (Goods.recommend eq true) and (Goods.status eq 1) }
                .orderBy(Goods.sortNum, SortOrder.ASC)
                .limit(4)
                .map { row ->
                    MiniFood(
                        id = row[Goods.id],
                        typeId = row[Goods.typeId],
                        title = row[Goods.title],
                        mainPic = Img.full(call, row[Goods.mainPic]) ?: "",
                        price = row[Goods.price]
                    )
                }

            call.respond(
                ok(
                    HomeData(
                        banner = banners,
                        mealScenes = mealScenes,
                        hotRecipes = hotRecipes,
                        recommendFoods = recommendFoods
                    )
                )
            )
        }
    }

    post("foods/type/list") {
        // 保留：给其他模块（如商品筛选）用
        @Serializable data class CategoryItem(
            val id: Long, val name: String, val parentId: Long,
            val pic: String, val sortNum: Int
        )

        suspendTransaction {
            val list = org.lzy.shop.server.config.Categories.selectAll()
                .orderBy(org.lzy.shop.server.config.Categories.parentId, SortOrder.ASC)
                .orderBy(org.lzy.shop.server.config.Categories.sortNum, SortOrder.ASC)
                .map { row ->
                    CategoryItem(
                        id = row[org.lzy.shop.server.config.Categories.id],
                        name = row[org.lzy.shop.server.config.Categories.name],
                        parentId = row[org.lzy.shop.server.config.Categories.parentId],
                        pic = Img.full(call, row[org.lzy.shop.server.config.Categories.pic] ?: "") ?: "",
                        sortNum = row[org.lzy.shop.server.config.Categories.sortNum]
                    )
                }

            call.respond(ok(list))
        }
    }
}
