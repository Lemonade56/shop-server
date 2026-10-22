package org.lzy.shop.server.route

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.common.Img
import org.lzy.shop.server.common.PageResult
import org.lzy.shop.server.common.ResultCode
import org.lzy.shop.server.common.fail
import org.lzy.shop.server.common.ok
import org.lzy.shop.server.config.Goods
import org.lzy.shop.server.config.RecipeIngredients
import org.lzy.shop.server.config.RecipeSteps
import org.lzy.shop.server.config.Recipes

/**
 * 菜谱相关路由 —— App 端菜谱 Tab 专用
 *
 * 接口列表（均免登录）：
 *   GET  app/recipe/categories  菜谱分类列表（全部可用分类）
 *   GET  app/recipe/list         菜谱列表（可按 category 筛选，支持 keyword 搜索）
 *   GET  app/recipe/detail       菜谱详情（含食材、做法步骤）
 */
fun Route.configureRecipesRoutes() {

    // 菜谱分类列表：distinct 返回所有上架菜谱的分类
    get("recipe/categories") {
        suspendTransaction {
            val cats = Recipes.select(Recipes.category)
                .where { Recipes.status eq 1 }
                .groupBy(Recipes.category)
                .map { it[Recipes.category] }
                .sorted()

            call.respond(ok(listOf("全部") + cats))
        }
    }

    // 菜谱列表：支持分类筛选 + 关键词搜索 + 分页
    get("recipe/list") {
        val category = call.request.queryParameters["category"]?.takeIf { it.isNotBlank() && it != "全部" }
        val keyword = call.request.queryParameters["keyword"]?.takeIf { it.isNotBlank() }
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1

        @Serializable data class RecipeItem(
            val id: Long, val name: String, val pic: String,
            val category: String, val difficulty: Int, val time: Int,
            val servings: Int, val author: String? = null
        )

        suspendTransaction {
            // 先构造 WHERE 条件，把所有 filter 都 AND 组合在一起
            val list = Recipes.selectAll().where {
                var cond = (Recipes.status eq 1)
                if (category != null) cond = cond and (Recipes.category eq category)
                if (keyword != null) cond = cond and (Recipes.name like "%$keyword%")
                cond
            }
            val total = list.count()
            val items = list.orderBy(Recipes.sortNum, SortOrder.ASC)
                .limit(10).offset(((page - 1) * 10).toLong())
                .map { row ->
                    RecipeItem(
                        id = row[Recipes.id],
                        name = row[Recipes.name],
                        pic = Img.full(call, row[Recipes.pic]) ?: "",
                        category = row[Recipes.category],
                        difficulty = row[Recipes.difficulty],
                        time = row[Recipes.time],
                        servings = row[Recipes.servings],
                        author = row[Recipes.author]
                    )
                }

            call.respond(ok(PageResult(total, items)))
        }
    }

    // 菜谱详情：含食材清单 + 做法步骤
    get("recipe/detail") {
        val id = call.request.queryParameters["id"]?.toLongOrNull()
            ?: return@get call.respond(fail<String>(ResultCode.BAD_REQUEST, "id 参数无效"))

        @Serializable data class Ingredient(
            val id: Long, val ingredientName: String,
            val quantity: String, val goodsId: Long? = null,
            val goodsTitle: String? = null
        )
        @Serializable data class Step(
            val stepNo: Int, val content: String, val pic: String? = null
        )
        @Serializable data class RecipeDetail(
            val id: Long, val name: String, val pic: String,
            val description: String? = null, val category: String,
            val difficulty: Int, val time: Int,
            val servings: Int, val author: String? = null,
            val ingredients: List<Ingredient>,
            val steps: List<Step>
        )

        suspendTransaction {
            val row = Recipes.selectAll().where { Recipes.id eq id }.firstOrNull()
                ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "菜谱不存在"))

            // 食材清单
            val ingredientRows = RecipeIngredients.selectAll().where { RecipeIngredients.recipeId eq id }
                .orderBy(RecipeIngredients.sortNum, SortOrder.ASC)

            // 一次性批量查出所有关联的商品标题，避免 N+1
            val goodsIds = ingredientRows.mapNotNull { it[RecipeIngredients.goodsId] }.distinct()
            val goodsTitleMap = if (goodsIds.isNotEmpty()) {
                Goods.selectAll().where { Goods.id inList goodsIds }
                    .associate { it[Goods.id] to it[Goods.title] }
            } else emptyMap()

            val ingredients = ingredientRows.map { ing ->
                val gid = ing[RecipeIngredients.goodsId]
                Ingredient(
                    id = ing[RecipeIngredients.id],
                    ingredientName = ing[RecipeIngredients.ingredientName],
                    quantity = ing[RecipeIngredients.quantity],
                    goodsId = gid,
                    goodsTitle = gid?.let { goodsTitleMap[it] }
                )
            }

            // 做法步骤
            val steps = RecipeSteps.selectAll().where { RecipeSteps.recipeId eq id }
                .orderBy(RecipeSteps.sortNum, SortOrder.ASC)
                .map { step ->
                    Step(
                        stepNo = step[RecipeSteps.stepNo],
                        content = step[RecipeSteps.content],
                        pic = Img.full(call, step[RecipeSteps.pic])
                    )
                }

            call.respond(
                ok(
                    RecipeDetail(
                        id = row[Recipes.id],
                        name = row[Recipes.name],
                        pic = Img.full(call, row[Recipes.pic]) ?: "",
                        description = row[Recipes.description],
                        category = row[Recipes.category],
                        difficulty = row[Recipes.difficulty],
                        time = row[Recipes.time],
                        servings = row[Recipes.servings],
                        author = row[Recipes.author],
                        ingredients = ingredients,
                        steps = steps
                    )
                )
            )
        }
    }
}
