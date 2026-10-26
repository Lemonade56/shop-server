package org.lzy.shop.server.route

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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
import org.lzy.shop.server.config.BrowseHistory
import org.lzy.shop.server.config.Recipes

/**
 * 厨友社区 & 浏览历史路由
 *
 * 接口列表：
 *   浏览历史（需登录）：
 *     POST app/community/history/add          添加浏览记录
 *     POST app/community/history/page         浏览历史列表（分页）
 *     POST app/community/history/delete       删除单条浏览记录
 *     POST app/community/history/clear       清空浏览历史
 *
 *   厨友社区（Demo 模式，免登录）：
 *     GET  app/community/post/list            社区帖子列表（热门/最新）
 *     GET  app/community/post/detail          帖子详情
 *     POST app/community/post/comment/list   帖子评论分页
 */
fun Route.configureCommunityRoutes() {

    // ===== 浏览历史（需登录）=====
    authenticate("auth-jwt") {

        post("community/history/add") {
            @Serializable data class Req(val recipeId: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()
            val now = System.currentTimeMillis().toString()

            suspendTransaction {
                // 菜谱必须存在
                val recipe = Recipes.selectAll().where { Recipes.id eq req.recipeId }.firstOrNull()
                    ?: return@suspendTransaction call.respond(fail<String>(ResultCode.NOT_FOUND, "菜谱不存在"))

                // 如果已经浏览过，先删旧记录再插入新的（去重，保持最新浏览顺序）
                BrowseHistory.deleteWhere {
                    (BrowseHistory.userId eq userId) and (BrowseHistory.recipeId eq req.recipeId)
                }

                BrowseHistory.insert {
                    it[BrowseHistory.userId] = userId
                    it[BrowseHistory.recipeId] = req.recipeId
                    it[BrowseHistory.createTime] = now
                }

                // 只保留最近 100 条，超出的删除最旧的
                val total = BrowseHistory.selectAll().where { BrowseHistory.userId eq userId }.count()
                if (total > 100) {
                    val toDelete = BrowseHistory.selectAll().where { BrowseHistory.userId eq userId }
                        .orderBy(BrowseHistory.createTime, SortOrder.ASC)
                        .limit((total - 100).toInt())
                        .map { it[BrowseHistory.id] }
                    BrowseHistory.deleteWhere { BrowseHistory.id inList toDelete }
                }

                call.respond(okMsg("记录浏览"))
            }
        }

        post("community/history/page") {
            @Serializable data class Req(val page: Int = 1)
            val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

            @Serializable data class HistoryItem(
                val id: Long, val recipeId: Long, val name: String,
                val pic: String, val time: Int, val difficulty: Int,
                val createTime: String
            )

            val userId = call.currentUserId()

            suspendTransaction {
                val total = BrowseHistory.selectAll().where { BrowseHistory.userId eq userId }.count()

                val historyRows = BrowseHistory.selectAll().where { BrowseHistory.userId eq userId }
                    .orderBy(BrowseHistory.createTime, SortOrder.DESC)
                    .limit(10).offset(((req.page - 1) * 10).toLong())
                    .toList()

                val recipeIds = historyRows.map { it[BrowseHistory.recipeId] }.distinct()
                val recipeMap = if (recipeIds.isNotEmpty()) {
                    Recipes.selectAll().where { Recipes.id inList recipeIds }
                        .associate { it[Recipes.id] to it }
                } else emptyMap()

                val list = historyRows.map { row ->
                    val r = recipeMap[row[BrowseHistory.recipeId]]
                    HistoryItem(
                        id = row[BrowseHistory.id],
                        recipeId = row[BrowseHistory.recipeId],
                        name = r?.get(Recipes.name) ?: "",
                        pic = Img.full(call, r?.get(Recipes.pic) ?: "") ?: "",
                        time = r?.get(Recipes.time) ?: 0,
                        difficulty = r?.get(Recipes.difficulty) ?: 0,
                        createTime = row[BrowseHistory.createTime]
                    )
                }

                call.respond(ok(PageResult(total, list)))
            }
        }

        post("community/history/delete") {
            @Serializable data class Req(val id: Long)
            val req = runCatching { call.receive<Req>() }.getOrNull()
                ?: return@post call.respond(fail<String>(ResultCode.BAD_REQUEST, "请求参数格式错误"))

            val userId = call.currentUserId()

            suspendTransaction {
                val deleted = BrowseHistory.deleteWhere {
                    (BrowseHistory.id eq req.id) and (BrowseHistory.userId eq userId)
                }
                if (deleted == 0) {
                    call.respond(fail<String>(ResultCode.NOT_FOUND, "浏览记录不存在"))
                } else {
                    call.respond(okMsg("删除浏览记录"))
                }
            }
        }

        post("community/history/clear") {
            val userId = call.currentUserId()

            suspendTransaction {
                BrowseHistory.deleteWhere { BrowseHistory.userId eq userId }
                call.respond(okMsg("清空浏览历史"))
            }
        }
    }

    // ===== 厨友社区（Demo 模式，免登录）=====
    //
    // 完整实现需要帖子/评论/点赞/关注等表结构和 WebSocket 实时通知，
    // 当前先用 Demo 硬编码数据骨架，接口结构对齐 App 端。

    get("community/post/list") {
        // 参数：sort = "hot"(热门) / "latest"(最新)，page 页码
        val sort = call.request.queryParameters["sort"]?.takeIf { it == "hot" || it == "latest" } ?: "hot"
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1

        @Serializable data class PostItem(
            val id: Long, val title: String, val content: String,
            val cover: String, val author: String, val avatar: String,
            val likeCount: Int, val commentCount: Int, val isLiked: Boolean,
            val createTime: String
        )

        // Demo 数据：固定 8 条，按 sort 排序后分页
        val demoPosts = listOf(
            PostItem(1, "新手必看的 10 道快手菜", "每天不知道吃什么？这里有 10 道菜，15 分钟就能搞定一顿饭！",
                "https://picsum.photos/seed/community-1/600/400", "厨房小白", "",
                328, 45, false, "2小时前"),
            PostItem(2, "自制番茄炒蛋的秘密", "番茄炒蛋人人都会，但要做到酸甜可口、鸡蛋嫩滑，有几个小技巧你可能不知道~",
                "https://picsum.photos/seed/community-2/600/400", "美食厨娘", "",
                256, 38, false, "4小时前"),
            PostItem(3, "减脂期吃什么？这 5 道菜低卡又好吃", "最近在减脂，研究了不少低卡菜谱，这几道真心推荐！",
                "https://picsum.photos/seed/community-3/600/400", "健身教练", "",
                189, 32, false, "6小时前"),
            PostItem(4, "家庭版红烧肉，肥而不腻入口即化", "做了十多年红烧肉，今天分享我的独家做法，新手也能一次成功！",
                "https://picsum.photos/seed/community-4/600/400", "家宴大厨", "",
                412, 67, false, "8小时前"),
            PostItem(5, "一周早餐不重样 | 第 3 天：蔬菜鸡蛋饼", "今天给大家带来一款超简单的蔬菜鸡蛋饼，5 分钟搞定早餐~",
                "https://picsum.photos/seed/community-5/600/400", "早餐达人", "",
                145, 21, false, "10小时前"),
            PostItem(6, "第一次尝试烘焙，成功做出戚风蛋糕！", "作为一个烘焙新手，第一次做戚风蛋糕就成功了，太开心啦！",
                "https://picsum.photos/seed/community-6/600/400", "烘焙小白", "",
                98, 15, false, "昨天"),
            PostItem(7, "煲汤干货！这 10 种汤品适合冬天喝", "冬天来了，分享 10 款温暖滋补的汤品配方，家人都爱喝~",
                "https://picsum.photos/seed/community-7/600/400", "养生达人", "",
                223, 41, false, "昨天"),
            PostItem(8, "分享我的厨房神器，最后一个必买！", "用过很多厨房用品，这几个是我每天都在用的宝藏好物~",
                "https://picsum.photos/seed/community-8/600/400", "精致生活", "",
                167, 29, false, "2天前"),
        )

        val sorted = when (sort) {
            "hot" -> demoPosts.sortedByDescending { it.likeCount }
            "latest" -> demoPosts.sortedByDescending { it.id }
            else -> demoPosts
        }

        val pageSize = 10
        val total = sorted.size.toLong()
        val list = sorted.drop((page - 1) * pageSize).take(pageSize)

        call.respond(ok(PageResult(total, list)))
    }

    get("community/post/detail") {
        val id = call.request.queryParameters["id"]?.toLongOrNull()
            ?: return@get call.respond(fail<String>(ResultCode.BAD_REQUEST, "id 参数无效"))

        @Serializable data class PostDetail(
            val id: Long, val title: String, val content: String,
            val cover: String, val author: String, val avatar: String,
            val likeCount: Int, val commentCount: Int, val isLiked: Boolean,
            val createTime: String
        )

        // 根据 id 返回对应 Demo 帖子
        val demoPost = PostDetail(
            id = id,
            title = "新手必看的 10 道快手菜",
            content = """
            每天不知道吃什么？这里有 10 道菜，15 分钟就能搞定一顿饭！

            1. 番茄炒蛋 - 经典中的经典
            2. 青椒土豆丝 - 清爽下饭
            3. 蒜蓉西兰花 - 健康美味
            4. 紫菜蛋花汤 - 暖胃暖心
            5. 香菇滑鸡 - 鲜美多汁
            6. 酸辣土豆丝 - 开胃解腻
            7. 黄瓜拌木耳 - 清爽凉菜
            8. 红烧豆腐 - 素菜也好吃
            9. 蛋炒饭 - 剩饭变美食
            10. 煎蛋吐司 - 5 分钟快手早餐

            大家还有什么快手菜推荐？评论区告诉我~
            """.trimIndent(),
            cover = "https://picsum.photos/seed/community-1/800/500",
            author = "厨房小白",
            avatar = "",
            likeCount = 328,
            commentCount = 45,
            isLiked = false,
            createTime = "2小时前"
        )

        call.respond(ok(demoPost))
    }

    post("community/post/comment/page") {
        @Serializable data class Req(val postId: Long = -1L, val page: Int = 1)
        val req = runCatching { call.receive<Req>() }.getOrDefault(Req())

        @Serializable data class CommentItem(
            val id: Long, val postId: Long, val nickname: String,
            val avatarUrl: String, val content: String, val createTime: String
        )

        // Demo 评论数据
        val demoComments = listOf(
            CommentItem(1, req.postId, "吃货小哥", "", "番茄炒蛋确实是最经典的！", "1小时前"),
            CommentItem(2, req.postId, "美食厨娘", "", "好棒的分享，已收藏！", "1小时前"),
            CommentItem(3, req.postId, "健身教练", "", "求低脂版本的快手菜~", "30分钟前"),
            CommentItem(4, req.postId, "厨房新手", "", "请问煎蛋吐司用什么面包比较好？", "20分钟前"),
            CommentItem(5, req.postId, "烘焙达人", "", "已关注，期待更多分享！", "10分钟前"),
        )

        call.respond(ok(PageResult(demoComments.size.toLong(), demoComments)))
    }
}
