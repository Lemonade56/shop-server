package org.lzy.shop.server.route

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.lzy.shop.server.auth.currentUserId
import org.lzy.shop.server.common.*
import org.lzy.shop.server.config.Addresses

/**
 * 收货地址路由 —— 对齐 App 端 AddressApi.kt
 *
 * 全部接口需要登录。
 */
fun Route.configureAddressRoutes() {

    authenticate("auth-jwt") {

        get("user/address/default") {
            val userId = call.currentUserId()

            @Serializable data class Address(
                val id: Long, val receiver: String, val phone: String,
                val province: String, val city: String, val district: String,
                val detail: String, val isDefault: Boolean
            )

            suspendTransaction {
                val row = Addresses.selectAll().where {
                    (Addresses.userId eq userId) and (Addresses.isDefault eq true)
                }.firstOrNull() ?: Addresses.selectAll().where { Addresses.userId eq userId }.firstOrNull()

                if (row == null) {
                    return@suspendTransaction call.respond(ok<String?>(null))
                }

                call.respond(
                    ok(
                        Address(
                            id = row[Addresses.id],
                            receiver = row[Addresses.receiver],
                            phone = row[Addresses.phone],
                            province = row[Addresses.province],
                            city = row[Addresses.city],
                            district = row[Addresses.district],
                            detail = row[Addresses.detail],
                            isDefault = row[Addresses.isDefault]
                        )
                    )
                )
            }
        }

        post("user/address/list") {
            @Serializable data class Req(val page: Int = 1)
            val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

            @Serializable data class Address(
                val id: Long, val receiver: String, val phone: String,
                val province: String, val city: String, val district: String,
                val detail: String, val isDefault: Boolean
            )

            val userId = call.currentUserId()

            suspendTransaction {
                val list = Addresses.selectAll().where { Addresses.userId eq userId }
                    .orderBy(Addresses.isDefault, SortOrder.DESC)
                    .map { row ->
                        Address(
                            id = row[Addresses.id],
                            receiver = row[Addresses.receiver],
                            phone = row[Addresses.phone],
                            province = row[Addresses.province],
                            city = row[Addresses.city],
                            district = row[Addresses.district],
                            detail = row[Addresses.detail],
                            isDefault = row[Addresses.isDefault]
                        )
                    }

                call.respond(ok(list))
            }
        }

        post("user/address/add") {
            @Serializable data class Req(
                val receiver: String, val phone: String,
                val province: String, val city: String, val district: String,
                val detail: String, val isDefault: Boolean = false
            )
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                // 如果设为默认，先把其他地址的默认去掉
                if (req.isDefault) {
                    Addresses.update({ (Addresses.userId eq userId) and (Addresses.isDefault eq true) }) {
                        it[Addresses.isDefault] = false
                    }
                }

                Addresses.insert {
                    it[Addresses.userId] = userId
                    it[Addresses.receiver] = req.receiver
                    it[Addresses.phone] = req.phone
                    it[Addresses.province] = req.province
                    it[Addresses.city] = req.city
                    it[Addresses.district] = req.district
                    it[Addresses.detail] = req.detail
                    it[Addresses.isDefault] = req.isDefault
                }

                call.respond(okMsg("新增地址"))
            }
        }

        post("user/address/update") {
            @Serializable data class Req(
                val id: Long, val receiver: String, val phone: String,
                val province: String, val city: String, val district: String,
                val detail: String, val isDefault: Boolean = false
            )
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                if (req.isDefault) {
                    Addresses.update({ (Addresses.userId eq userId) and (Addresses.isDefault eq true) }) {
                        it[Addresses.isDefault] = false
                    }
                }

                val updated = Addresses.update({ (Addresses.id eq req.id) and (Addresses.userId eq userId) }) {
                    it[Addresses.receiver] = req.receiver
                    it[Addresses.phone] = req.phone
                    it[Addresses.province] = req.province
                    it[Addresses.city] = req.city
                    it[Addresses.district] = req.district
                    it[Addresses.detail] = req.detail
                    it[Addresses.isDefault] = req.isDefault
                }

                if (updated == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "地址不存在"))
                } else {
                    call.respond(okMsg("修改地址"))
                }
            }
        }

        post("user/address/delete") {
            @Serializable data class Req(val id: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                val deleted = Addresses.deleteWhere {
                    (Addresses.id eq req.id) and (Addresses.userId eq userId)
                }

                if (deleted == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "地址不存在"))
                } else {
                    call.respond(okMsg("删除地址"))
                }
            }
        }
    }
}
