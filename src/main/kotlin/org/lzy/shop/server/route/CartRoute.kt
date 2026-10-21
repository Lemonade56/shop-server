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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.lzy.shop.server.auth.currentUserId
import org.lzy.shop.server.common.*
import org.lzy.shop.server.config.Cart
import org.lzy.shop.server.config.Goods as GoodsTable

/**
 * 购物车路由 —— 对齐 App 端购物车 API
 *
 * Cart 表字段：id / userId / goodsId / specId / quantity / isSelected / createTime / updateTime
 *
 * 接口列表（均需登录）：
 *   POST app/cart/list           购物车列表（带商品标题/价格/主图）
 *   POST app/cart/add             加入购物车
 *   POST app/cart/update          修改数量
 *   POST app/cart/delete          删除（支持批量）
 *   POST app/cart/select          勾选/取消勾选（支持全选）
 *   POST app/cart/clearChecked    清空已勾选
 */
fun Route.configureCartRoutes() {

    authenticate("auth-jwt") {

        // ===== 购物车列表 =====
        post("cart/list") {
            @Serializable
            data class CartItem(
                val id: Long, val goodsId: Long, val specId: Long?,
                val quantity: Int, val isSelected: Boolean,
                val title: String, val mainPic: String, val price: Int
            )

            val userId = call.currentUserId()

            suspendTransaction {
                val list = Cart.innerJoin(GoodsTable)
                    .selectAll().where { Cart.userId eq userId }
                    .orderBy(Cart.createTime, SortOrder.DESC)
                    .map { row ->
                        CartItem(
                            id = row[Cart.id],
                            goodsId = row[Cart.goodsId],
                            specId = row[Cart.specId],
                            quantity = row[Cart.quantity],
                            isSelected = row[Cart.isSelected],
                            title = row[GoodsTable.title],
                            mainPic = Img.full(call, row[GoodsTable.mainPic]) ?: "",
                            price = row[GoodsTable.price]
                        )
                    }

                call.respond(ok(list))
            }
        }

        // ===== 加入购物车 =====
        post("cart/add") {
            @Serializable
            data class Req(
                val goodsId: Long,
                val specId: Long? = null,
                val quantity: Int = 1
            )

            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                // 商品必须存在
                val goods = GoodsTable.selectAll().where { GoodsTable.id eq req.goodsId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "商品不存在"))

                // 同一商品 + 同一规格，如果已在购物车则累加数量
                val existed = Cart.selectAll().where {
                    (Cart.userId eq userId) and (Cart.goodsId eq req.goodsId) and (Cart.specId eq req.specId)
                }.firstOrNull()

                if (existed != null) {
                    Cart.update({ Cart.id eq existed[Cart.id] }) {
                        it[Cart.quantity] = existed[Cart.quantity] + req.quantity
                        it[Cart.updateTime] = now
                    }
                } else {
                    Cart.insert {
                        it[Cart.userId] = userId
                        it[Cart.goodsId] = req.goodsId
                        it[Cart.specId] = req.specId
                        it[Cart.quantity] = req.quantity
                        it[Cart.isSelected] = true
                        it[Cart.createTime] = now
                        it[Cart.updateTime] = now
                    }
                }

                call.respond(okMsg("加入购物车"))
            }
        }

        // ===== 修改数量 =====
        post("cart/update") {
            @Serializable
            data class Req(val id: Long, val quantity: Int)

            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            if (req.quantity <= 0) {
                return@post call.respond(fail<String>(message = "数量必须大于 0"))
            }

            val userId = call.currentUserId()
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                val updated = Cart.update({ (Cart.id eq req.id) and (Cart.userId eq userId) }) {
                    it[Cart.quantity] = req.quantity
                    it[Cart.updateTime] = now
                }

                if (updated == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "购物车记录不存在"))
                } else {
                    call.respond(okMsg("修改数量"))
                }
            }
        }

        // ===== 删除（支持批量） =====
        post("cart/delete") {
            @Serializable
            data class Req(val ids: List<Long>)

            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                Cart.deleteWhere { (Cart.userId eq userId) and (Cart.id inList req.ids) }
                call.respond(okMsg("删除购物车"))
            }
        }

        // ===== 勾选/取消勾选 =====
        //
        // 请求 JSON：
        //   { "id": 1, "isSelected": true }     // 勾/取消某一条
        //   { "isSelected": true, "all": true } // 全选/取消全选
        post("cart/select") {
            @Serializable
            data class Req(
                val id: Long? = null,
                val isSelected: Boolean = true,
                val all: Boolean = false
            )

            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                if (req.all) {
                    Cart.update({ Cart.userId eq userId }) {
                        it[Cart.isSelected] = req.isSelected
                        it[Cart.updateTime] = now
                    }
                } else if (req.id != null) {
                    Cart.update({ (Cart.id eq req.id) and (Cart.userId eq userId) }) {
                        it[Cart.isSelected] = req.isSelected
                        it[Cart.updateTime] = now
                    }
                } else {
                    return@suspendTransaction call.respond(fail<String>(ResultCode.BAD_REQUEST, "参数错误"))
                }

                call.respond(okMsg("更新选中状态"))
            }
        }

        // ===== 清空已勾选（购物车结算成功后调用） =====
        post("cart/clearChecked") {
            val userId = call.currentUserId()

            suspendTransaction {
                Cart.deleteWhere { (Cart.userId eq userId) and (Cart.isSelected eq true) }
                call.respond(okMsg("清空已勾选"))
            }
        }
    }
}
