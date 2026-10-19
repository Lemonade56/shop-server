package org.lzy.shop.server.route

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.common.PageResult
import org.lzy.shop.server.common.ResultCode
import org.lzy.shop.server.common.fail
import org.lzy.shop.server.common.ok
import org.lzy.shop.server.config.Goods
import org.lzy.shop.server.config.RecipeIngredients
import org.lzy.shop.server.config.Recipes

/**
 * 菜谱相关路由 —— App 端菜谱 Tab 专用
 *
 * 接口列表（均免登录）：
 *   GET  app/recipe/list        菜谱列表（可按分类筛选）
 *   GET  app/recipe/detail      菜谱详情（含食材列表）
 *   GET  app/recipe/categories  菜谱分类列表（去重）
 */
fun Route.configureRecipesRoutes() {

    get("recipe/categories") {
        suspendTransaction {
            val cats = Recipes.select(Recipes.category)
                .where { Recipes.status eq 1 }
                .groupBy(Recipes.category)
                .map { it[Recipes.category] }
                .sorted()

            call.respond(ok(cats))
        }
    }

    get("recipe/list") {
        val category = call.request.queryParameters["category"]?.takeIf { it.isNotBlank() }
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1

        @Serializable data class RecipeItem(
            val id: Long, val name: String, val pic: String,
            val category: String, val difficulty: Int, val time: Int
        )

        suspendTransaction {
            var query = Recipes.selectAll().where { Recipes.status eq 1 }
            if (category != null) {
                query = Recipes.selectAll().where {
                    (Recipes.status eq 1) and (Recipes.category eq category)
                }
            }

            val total = query.count()
            val list = query.orderBy(Recipes.sortNum, SortOrder.ASC)
                .limit(10).offset(((page - 1) * 10).toLong())
                .map { row ->
                    RecipeItem(
                        id = row[Recipes.id],
                        name = row[Recipes.name],
                        pic = row[Recipes.pic],
                        category = row[Recipes.category],
                        difficulty = row[Recipes.difficulty],
                        time = row[Recipes.time]
                    )
                }

            call.respond(ok(PageResult(total, list)))
        }
    }

    get("recipe/detail") {
        val id = call.request.queryParameters["id"]?.toLongOrNull()
            ?: return@get call.respond(fail<String>(ResultCode.BAD_REQUEST, "id 参数无效"))

        @Serializable data class Ingredient(
            val id: Long, val ingredientName: String,
            val quantity: String, val goodsId: Long? = null,
            val goodsTitle: String? = null
        )
        @Serializable data class RecipeDetail(
            val id: Long, val name: String, val pic: String,
            val description: String? = null, val category: String,
            val difficulty: Int, val time: Int,
            val ingredients: List<Ingredient>
        )

        suspendTransaction {
            val row = Recipes.selectAll().where { Recipes.id eq id }.firstOrNull()
                ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "菜谱不存在"))

            // 查食材 + 关联商品（如果有 goodsId 则联查商品标题）
            val ingredients = RecipeIngredients.selectAll().where { RecipeIngredients.recipeId eq id }
                .orderBy(RecipeIngredients.sortNum, SortOrder.ASC)
                .map { ing ->
                    val gid = ing[RecipeIngredients.goodsId]
                    var goodsTitle: String? = null
                    if (gid != null) {
                        val g = Goods.selectAll().where { Goods.id eq gid }.firstOrNull()
                        goodsTitle = g?.get(Goods.title)
                    }
                    Ingredient(
                        id = ing[RecipeIngredients.id],
                        ingredientName = ing[RecipeIngredients.ingredientName],
                        quantity = ing[RecipeIngredients.quantity],
                        goodsId = gid,
                        goodsTitle = goodsTitle
                    )
                }

            call.respond(
                ok(
                    RecipeDetail(
                        id = row[Recipes.id],
                        name = row[Recipes.name],
                        pic = row[Recipes.pic],
                        description = row[Recipes.description],
                        category = row[Recipes.category],
                        difficulty = row[Recipes.difficulty],
                        time = row[Recipes.time],
                        ingredients = ingredients
                    )
                )
            )
        }
    }
}
