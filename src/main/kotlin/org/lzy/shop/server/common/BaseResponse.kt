package org.lzy.shop.server.common

import kotlinx.serialization.Serializable
import org.lzy.shop.server.common.ResultCode.SUCCESS

/**
 * 统一响应格式 —— 业务码与 HTTP 状态码对齐
 *
 * 业务码约定（与 HTTP 标准一致）：
 *   200  成功
 *   400  参数错误 / 请求格式错误
 *   401  未登录 / Token 无效
 *   403  无权限
 *   404  资源不存在
 *   422  业务校验失败（参数合法但业务不允许）
 *   500  服务器内部错误
 */
@Serializable
data class BaseResponse<T>(
    val data: T? = null,
    val code: Int = SUCCESS,
    val message: String = ""
) {
    val isSucceeded: Boolean get() = code == SUCCESS
}

/**
 * 通用分页响应 —— 所有分页接口统一返回此结构
 * 避免 ok(mapOf("total" to x, "list" to y)) 因混合类型推断出 Map<String, Any>
 */
@Serializable
data class PageResult<T>(
    val total: Long,
    val list: List<T>
)

object ResultCode {
    const val SUCCESS = 200
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val BUSINESS_ERROR = 422
    const val INTERNAL_ERROR = 500
}

// ===== 便捷构造函数 =====

fun <T> ok(data: T): BaseResponse<T> = BaseResponse(data = data)

fun ok(): BaseResponse<String> = BaseResponse(data = "ok")

fun <T> fail(
    code: Int = ResultCode.BUSINESS_ERROR,
    message: String = "操作失败"
): BaseResponse<T> = BaseResponse(code = code, message = message)

inline fun <T> okOr(
    value: T?,
    errorMessage: String = "数据不存在"
): BaseResponse<T> = if (value != null) ok(value) else fail(message = errorMessage)
