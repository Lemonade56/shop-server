package org.lzy.shop.server.route

import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.auth.currentUserIdOrNull
import org.lzy.shop.server.common.Img
import org.lzy.shop.server.common.ok
import org.lzy.shop.server.config.*
import java.time.LocalDateTime

/**
 * 首页路由 —— 对齐 App 首页视觉
 *
 * 对应 App 截图：
 *   顶部问候语 + 搜索栏（前端自行实现）
 *   4 个功能入口：当季推荐 / 营养膳食 / 我的收藏 / 厨友社区
 *   今日推荐大卡（主编推荐，每天轮换）
 *   用餐场景（快手早餐/轻食午餐/暖心晚餐...）
 *   热门菜谱（两列卡片）
 *   当季食材（横向滚动推荐 —— 第一张图底部的横向列表）
 */
fun Route.configureHomeRoutes() {

    // ===== 1. 首页聚合接口（无需登录） =====
    get("page/home") {
        @Serializable data class TodayRecommend(
            val recipeId: Long,
            val title: String,
            val subtitle: String,
            val description: String,
            val cover: String,
            val weekday: String,
            val ingredientCount: Int,
            val ingredientTotalPrice: Int
        )
        @Serializable data class MealScene(
            val id: Long, val name: String, val timeTag: String,
            val count: Int, val cover: String, val themeColor: String
        )
        @Serializable data class HotRecipeItem(
            val id: Long, val name: String, val cover: String,
            val time: Int, val likeCount: String
        )
        @Serializable data class SeasonalIngredient(
            val id: Long, val title: String, val subtitle: String,
            val cover: String, val price: Int, val unitLabel: String
        )
        @Serializable data class HomeFeature(
            val id: Long, val name: String, val icon: String, val color: String
        )
        @Serializable data class HomeData(
            val greeting: String,
            val weekday: String,
            val features: List<HomeFeature>,
            val todayRecommend: TodayRecommend,
            val mealScenes: List<MealScene>,
            val hotRecipes: List<HotRecipeItem>,
            val seasonalIngredients: List<SeasonalIngredient>
        )

        suspendTransaction {
            val now = LocalDateTime.now()
            val hour = now.hour
            val greeting = when (hour) {
                in 5..10 -> "早上好"
                in 11..13 -> "中午好"
                in 14..17 -> "下午好"
                in 18..22 -> "晚上好"
                else -> "夜深啦"
            }
            val weekdayCN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[now.dayOfWeek.value - 1]

            // —— 功能入口（4 个固定 icon）——
            val features = listOf(
                HomeFeature(1, "当季推荐", "season", "#FFB86B"),
                HomeFeature(2, "营养膳食", "nutrition", "#8FD1A3"),
                HomeFeature(3, "我的收藏", "star", "#F5C563"),
                HomeFeature(4, "厨友社区", "community", "#A8C5DA"),
            )

            // —— 今日推荐大卡 ——
            // 按星期几轮换一道菜谱，让 App 每天打开都有不同内容
            val weekdayIdx = now.dayOfWeek.value - 1
            val recipesRow = Recipes.selectAll().where { Recipes.status eq 1 }
                .orderBy(Recipes.sortNum, SortOrder.ASC)
                .limit(10).offset((weekdayIdx).toLong())
                .firstOrNull()
                ?: Recipes.selectAll().where { Recipes.status eq 1 }.firstOrNull()

            val today = if (recipesRow != null) {
                // 统计这道菜的食材数量 & 关联商品价格总和
                val rid = recipesRow[Recipes.id]
                val ingRows = RecipeIngredients.selectAll().where { RecipeIngredients.recipeId eq rid }
                    .toList()
                val ingredientCount = ingRows.size

                // 只累加带 goodsId 的食材价格，没有关联商品的按 0 算
                val goodsIds = ingRows.mapNotNull { it[RecipeIngredients.goodsId] }.distinct()
                val goodsPriceMap = if (goodsIds.isNotEmpty()) {
                    Goods.selectAll().where { Goods.id inList goodsIds }
                        .associate { it[Goods.id] to it[Goods.price] }
                } else emptyMap()
                val totalPrice = ingRows.sumOf { row ->
                    row[RecipeIngredients.goodsId]?.let { goodsPriceMap[it] } ?: 0
                }

                TodayRecommend(
                    recipeId = rid,
                    title = recipesRow[Recipes.name],
                    subtitle = "今日推荐 · $weekdayCN",
                    description = recipesRow[Recipes.description] ?: "简单、快手、好吃，今天就做它。",
                    cover = Img.full(call, recipesRow[Recipes.pic]) ?: "",
                    weekday = weekdayCN,
                    ingredientCount = ingredientCount.coerceAtLeast(4),
                    ingredientTotalPrice = totalPrice.coerceAtLeast(6800)
                )
            } else {
                TodayRecommend(0, "敬请期待", "今日推荐", "暂无内容", "", weekdayCN, 4, 6800)
            }

            // —— 用餐场景（4 个固定入口，覆盖主题色 / 耗时 / 数量）——
            val mealScenes = listOf(
                MealScene(1, "快手早餐", "15min", 268, "https://picsum.photos/seed/scene-breakfast/600/600", "#F4A259"),
                MealScene(2, "轻食午餐", "30min", 192, "https://picsum.photos/seed/scene-lunch/600/600", "#5B8C6F"),
                MealScene(3, "暖心晚餐", "45min", 304, "https://picsum.photos/seed/scene-dinner/600/600", "#D97B52"),
                MealScene(4, "烘焙甜品", "60min", 128, "https://picsum.photos/seed/scene-bake/600/600", "#E8B4B8"),
            )

            // —— 热门菜谱（两列卡片，取前 6 张）——
            val hotRecipes = Recipes.selectAll().where { Recipes.status eq 1 }
                .orderBy(Recipes.sortNum, SortOrder.ASC)
                .limit(6)
                .map { row ->
                    HotRecipeItem(
                        id = row[Recipes.id],
                        name = row[Recipes.name],
                        cover = Img.full(call, row[Recipes.pic]) ?: "",
                        time = row[Recipes.time],
                        likeCount = "%.1fk".format((800 + row[Recipes.id] * 123) / 1000f)
                    )
                }

            // —— 当季食材（取 Goods 表里 category=生鲜果蔬，按 sortNum 排前 8 个，横向滚动）——
            // 动态找一下生鲜果蔬分类的 id
            val freshCat = Categories.selectAll().where { Categories.name eq "生鲜果蔬" }.firstOrNull()
            val freshTypeId = freshCat?.get(Categories.id)
            val seasonalIngredients = if (freshTypeId != null) {
                Goods.selectAll().where { (Goods.typeId eq freshTypeId) and (Goods.status eq 1) }
                    .orderBy(Goods.sortNum, SortOrder.ASC)
                    .limit(8)
                    .map { row ->
                        SeasonalIngredient(
                            id = row[Goods.id],
                            title = row[Goods.title],
                            subtitle = row[Goods.subTitle] ?: "农场直采",
                            cover = Img.full(call, row[Goods.mainPic]) ?: "",
                            price = row[Goods.price],
                            unitLabel = when ((row[Goods.id] % 3).toInt()) {
                                0 -> "500g"
                                1 -> "300g"
                                else -> "1份"
                            }
                        )
                    }
            } else emptyList()

            call.respond(
                ok(
                    HomeData(
                        greeting = greeting,
                        weekday = weekdayCN,
                        features = features,
                        todayRecommend = today,
                        mealScenes = mealScenes,
                        hotRecipes = hotRecipes,
                        seasonalIngredients = seasonalIngredients
                    )
                )
            )
        }
    }

    // ===== 菜谱 Tab 聚合：主编推荐大卡 + 最新上架列表 =====
    get("page/recipes") {
        @Serializable data class EditorPick(
            val id: Long, val title: String, val cover: String,
            val description: String, val time: Int, val difficulty: String,
            val difficultyLevel: Int, val servings: Int, val rating: Float,
            val isCollected: Boolean
        )
        @Serializable data class LatestRecipe(
            val id: Long, val name: String, val cover: String,
            val description: String, val time: Int, val rating: Float
        )
        @Serializable data class RecipesPageData(
            val categories: List<String>,
            val editorPick: EditorPick,
            val latestRecipes: List<LatestRecipe>
        )

        suspendTransaction {
            val userId = call.currentUserIdOrNull()
            // 如果登录了，取用户已收藏菜谱集合，用来标记 isCollected
            val collectedIds = if (userId != null) {
                RecipeCollections.selectAll().where { RecipeCollections.userId eq userId }
                    .map { it[RecipeCollections.recipeId] }.toSet()
            } else emptySet()

            val categories = listOf("全部") + Recipes.select(Recipes.category)
                .where { Recipes.status eq 1 }
                .groupBy(Recipes.category)
                .map { it[Recipes.category] }
                .sorted()

            val editorRow = Recipes.selectAll().where { Recipes.status eq 1 }
                .orderBy(Recipes.sortNum, SortOrder.ASC)
                .firstOrNull()
            val editor = editorRow?.let { r ->
                val rid = r[Recipes.id]
                EditorPick(
                    id = rid,
                    title = r[Recipes.name],
                    cover = Img.full(call, r[Recipes.pic]) ?: "",
                    description = r[Recipes.description] ?: "",
                    time = r[Recipes.time],
                    difficulty = listOf("简单", "中等", "困难")[r[Recipes.difficulty].coerceAtLeast(1).coerceAtMost(3) - 1],
                    difficultyLevel = r[Recipes.difficulty],
                    servings = r[Recipes.servings],
                    rating = "%.1f".format(4.7 + rid * 0.01f).toFloat(),
                    isCollected = rid in collectedIds
                )
            } ?: EditorPick(0, "", "", "", 0, "简单", 1, 2, 4.7f, false)

            val latest = Recipes.selectAll().where { Recipes.status eq 1 }
                .orderBy(Recipes.sortNum, SortOrder.ASC)
                .limit(6)
                .map { row ->
                    LatestRecipe(
                        id = row[Recipes.id],
                        name = row[Recipes.name],
                        cover = Img.full(call, row[Recipes.pic]) ?: "",
                        description = row[Recipes.description] ?: "",
                        time = row[Recipes.time],
                        rating = "%.1f".format(4.6 + row[Recipes.id] * 0.005f).toFloat()
                    )
                }

            call.respond(ok(RecipesPageData(categories, editor, latest)))
        }
    }

    // ===== 购物车「常和这些一起买」推荐接口（按加入购物车的商品类型，随机取 3 个同类商品）=====
    authenticate("auth-jwt") {
        get("cart/recommend") {
            @Serializable data class RecommendItem(
                val id: Long, val title: String, val cover: String, val price: Int
            )

            val userId = call.currentUserIdOrNull()
                ?: return@get call.respond(ok(emptyList<RecommendItem>()))

            suspendTransaction {
                // 找用户购物车里的商品，取 typeId 列表
                val cartTypeIds = Cart.selectAll().where { Cart.userId eq userId }
                    .mapNotNull { row ->
                        val gid = row[Cart.goodsId]
                        Goods.selectAll().where { Goods.id eq gid }.firstOrNull()?.get(Goods.typeId)
                    }.distinct()

                val candidates = if (cartTypeIds.isNotEmpty()) {
                    Goods.selectAll().where {
                        (Goods.typeId inList cartTypeIds) and (Goods.status eq 1)
                    }
                } else {
                    Goods.selectAll().where { Goods.status eq 1 }
                }

                // 排除已在购物车里的商品
                val cartGoodsIds = Cart.selectAll().where { Cart.userId eq userId }
                    .map { it[Cart.goodsId] }.toSet()

                val list = candidates.orderBy(Goods.sortNum, SortOrder.ASC)
                    .limit(12)
                    .filter { it[Goods.id] !in cartGoodsIds }
                    .shuffled()  // 每次请求随机一下，推荐不单调
                    .take(3)
                    .map { row ->
                        RecommendItem(
                            id = row[Goods.id],
                            title = row[Goods.title],
                            cover = Img.full(call, row[Goods.mainPic]) ?: "",
                            price = row[Goods.price]
                        )
                    }

                call.respond(ok(list))
            }
        }
    }

    // ===== 商品分类（保留：给筛选模块用）=====
    post("foods/type/list") {
        @Serializable data class CategoryItem(
            val id: Long, val name: String, val parentId: Long,
            val pic: String, val sortNum: Int
        )

        suspendTransaction {
            val list = Categories.selectAll()
                .orderBy(Categories.parentId, SortOrder.ASC)
                .orderBy(Categories.sortNum, SortOrder.ASC)
                .map { row ->
                    CategoryItem(
                        id = row[Categories.id],
                        name = row[Categories.name],
                        parentId = row[Categories.parentId],
                        pic = Img.full(call, row[Categories.pic] ?: "") ?: "",
                        sortNum = row[Categories.sortNum]
                    )
                }

            call.respond(ok(list))
        }
    }
}
