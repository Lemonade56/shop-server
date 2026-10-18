package org.lzy.shop.server.route

import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.lzy.shop.server.common.ok

/**
 * 文件上传路由 —— 对齐 App 端 UploadApi.kt
 *
 * App 端使用腾讯云 COS 直传模式：
 *   1. 先调后端接口获取临时上传凭证（SecretId / SecretKey / Token）
 *   2. 用拿到的凭证直接往 COS 上传文件（前端直传，不经过后端）
 *
 * 这里返回 Demo 凭证，真实对接需要：
 *   - 引入 腾讯云 COS SDK（Java）
 *   - 配置真实的 SecretId / SecretKey
 *   - 生成临时 STS 凭证（有效期建议 15-30 分钟）
 */
fun Route.configureUploadRoutes() {

    authenticate("auth-jwt") {

        post("base/comm/upload") {
            @Serializable data class Credentials(
                val tmpSecretId: String, val tmpSecretKey: String, val sessionToken: String
            )
            @Serializable data class OssUpload(
                val bucket: String = "demo-bucket-1250000000",
                val region: String = "ap-guangzhou",
                val dir: String = "shop/${System.currentTimeMillis() / 1000}",
                val expire: Long = System.currentTimeMillis() / 1000 + 1800,
                val credentials: Credentials = Credentials(
                    tmpSecretId = "DemoSecretIdxxxxxxxxxxxxx",
                    tmpSecretKey = "DemoSecretKeyxxxxxxxxxxxxx",
                    sessionToken = "DemoSessionTokenxxxxxxxxxxxxx"
                )
            )

            call.respond(ok(OssUpload()))
        }
    }
}
