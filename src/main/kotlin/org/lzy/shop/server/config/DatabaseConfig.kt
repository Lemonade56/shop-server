package org.lzy.shop.server.config

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * 数据库连接 + 建表
 *
 * 当前使用 H2 嵌入式数据库（文件模式），零安装，数据落在 ./data/shop 文件中。
 * 如果以后想换 MySQL / PostgreSQL，只改 [connect] 里的 URL 和 driver 即可。
 *
 * Exposed 是 Kotlin 官方出的 ORM，API 类型安全，比 JPA/Hibernate 轻量很多。
 */
object DatabaseConfig {

    fun connect() {
        val url = AppConfig.Db.URL
        val driver = when {
            url.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
            url.startsWith("jdbc:mysql:") -> "com.mysql.cj.jdbc.Driver"
            url.startsWith("jdbc:h2:") -> "org.h2.Driver"
            else -> "org.h2.Driver"
        }

        Database.connect(
            url = url,
            user = AppConfig.Db.USER,
            password = AppConfig.Db.PASSWORD,
            driver = driver
        )

        // 应用启动时自动建表（不存在则创建，存在则跳过）
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users,
                Addresses,
                Categories,
                Goods,
                Banners,
                GoodsSpecs,
                Comments,
                Cart,
                Orders,
                OrderItems,
                Coupons,
                UserCoupons,
                Collections,
                Notifications,
                Feedbacks,
                SearchKeywords
            )
            // 首次启动插入小米生态种子数据
            seedIfEmpty()
        }
    }

    /** 种子数据 —— 小米生态商品、分类、轮播图、优惠券等，首次启动自动灌入 */
    private fun seedIfEmpty() {
        if (Categories.selectAll().count() > 0) return

        val now = java.time.LocalDate.now().toString()  // yyyy-MM-dd 格式

        // ===== 分类（扁平结构，parentId=0 为顶级） =====
        data class Cat(val name: String, val parentId: Long, val sort: Int)
        val categories = listOf(
            // 顶级分类
            Cat("手机通讯", 0, 1),
            Cat("智能穿戴", 0, 2),
            Cat("智能家居", 0, 3),
            Cat("电脑办公", 0, 4),
            Cat("配件周边", 0, 5),
            Cat("生活电器", 0, 6),
            // 子分类 —— 后续关联商品用顶级分类 id
        )
        val catIds = mutableListOf<Long>()
        categories.forEach { c ->
            val id = Categories.insert {
                it[Categories.name] = c.name
                it[Categories.parentId] = c.parentId
                it[Categories.sortNum] = c.sort
            } get Categories.id
            catIds.add(id)
        }
        val catPhone = catIds[0]    // 手机通讯
        val catWear = catIds[1]     // 智能穿戴
        val catHome = catIds[2]     // 智能家居
        val catPc = catIds[3]       // 电脑办公
        val catAcc = catIds[4]      // 配件周边
        val catAppl = catIds[5]     // 生活电器

        // ===== 轮播图（小米官网真实商品图） =====
        val banners = listOf(
            Triple("https://aka.doubaocdn.com/s/lmEu6vIgn5", "小米15 Pro 旗舰新起点", 1),
            Triple("https://aka.doubaocdn.com/s/h9wUyM8kLB", "小米15 小尺寸旗舰", 2),
            Triple("https://aka.doubaocdn.com/s/KWZPilU3EY", "小米15 Pro 设计美学", 3),
            Triple("https://aka.doubaocdn.com/s/G4qmSnUgRU", "小米15 专业影像", 4),
            Triple("https://aka.doubaocdn.com/s/skCtGS5p3g", "小米15 Pro 核心特性", 5),
        )
        // 8 张经 GET 验证可加载的小米真实商品图，循环分配给全部 27 个商品
        val realPics = listOf(
            "https://aka.doubaocdn.com/s/lmEu6vIgn5",  // Xiaomi 15 Pro main
            "https://aka.doubaocdn.com/s/h9wUyM8kLB",  // Xiaomi 15 main
            "https://aka.doubaocdn.com/s/KWZPilU3EY",  // Xiaomi 15 Pro closeup
            "https://aka.doubaocdn.com/s/G4qmSnUgRU",  // Xiaomi 15 closeup
            "https://aka.doubaocdn.com/s/skCtGS5p3g",  // Xiaomi 15 Pro features
            "https://aka.doubaocdn.com/s/fLihKL8jNH",  // Xiaomi 15 features
            "https://aka.doubaocdn.com/s/DmV2rST0xL",  // Xiaomi 15 Pro screen
            "https://aka.doubaocdn.com/s/oJUgTQr2L4",  // Xiaomi 15 battery
        )
        banners.forEach { (pic, title, sort) ->
            Banners.insert {
                it[Banners.pic] = pic
                it[Banners.url] = ""
                it[Banners.title] = title
                it[Banners.sortNum] = sort
                it[Banners.status] = 1
            }
        }

        // ===== 商品（小米生态） =====
        data class G(
            val typeId: Long, val title: String, val subTitle: String,
            val mainPic: String, val price: Int, val sold: Int,
            val recommend: Boolean, val featured: Boolean, val sort: Int
        )
        val goods = listOf(
            // 小米手机
            G(catPhone, "小米15 Pro", "徕卡光学 Summilux 高速镜头 骁龙8至尊版",
              realPics[0], 499900, 15800, true, true, 1),
            G(catPhone, "小米15", "徕卡专业影像 小尺寸旗舰 骁龙8至尊版",
              realPics[1], 359900, 22300, true, true, 2),
            G(catPhone, "小米15 Ultra", "徕卡一英寸可变光圈 小米AISP 2.0",
              realPics[2], 599900, 8900, true, true, 3),
            G(catPhone, "Redmi K80 Pro", "骁龙8 Gen3 2K直屏 5500mAh",
              realPics[3], 329900, 38900, true, false, 4),
            G(catPhone, "Redmi K80", "骁龙8 Gen2 1.5K屏 5000mAh",
              realPics[4], 249900, 56700, true, false, 5),
            G(catPhone, "Redmi Turbo 4", "骁龙8s Gen3 35mm徕卡主摄",
              realPics[5], 199900, 42100, true, false, 6),
            G(catPhone, "小米Civi 5 Pro", "人像光影 轻薄旗舰 大曲面屏",
              realPics[6], 299900, 9200, false, false, 7),
            // 智能穿戴
            G(catWear, "小米手表 S3", "蓝宝石玻璃 AMOLED屏 GPS双频",
              realPics[7], 149900, 5600, true, false, 1),
            G(catWear, "小米手表 Watch 5 Pro", "双芯长续航 蓝宝石表镜",
              realPics[0], 249900, 3200, true, true, 2),
            G(catWear, "小米手环 9 Pro", "超大AMOLED屏 5ATM防水",
              realPics[1], 49900, 38900, true, true, 3),
            G(catWear, "小米手环 9 NFC版", "NFC公交卡 14天续航",
              realPics[2], 37900, 45600, true, false, 4),
            // 智能家居
            G(catHome, "小米空气净化器 Pro H", "甲醛数显 600CADR 米家联动",
              realPics[3], 149900, 3400, true, false, 1),
            G(catHome, "小米扫地机器人 S20 Pro", "全向激光 自动集尘 拖扫一体",
              realPics[4], 249900, 2800, true, true, 2),
            G(catHome, "米家空调 Pro 巨省电", "超一级能效 2匹 直流变频",
              realPics[5], 329900, 1800, true, true, 3),
            G(catHome, "小爱音箱 Pro", "红外遥控 立体声 超级小爱",
              realPics[6], 29900, 7800, false, false, 4),
            // 电脑办公
            G(catPc, "小米笔记本 Pro 15.6 2024", "酷睿Ultra 9 RTX4060 3.2K屏",
              realPics[7], 899900, 1200, true, false, 1),
            G(catPc, "Redmi Book 16 2024", "酷睿i5 16GB 1TB 2.5K 120Hz",
              realPics[0], 499900, 2300, true, true, 2),
            G(catPc, "小米显示器 27英寸 2K 180Hz", "IPS 400nit HDR400",
              realPics[1], 129900, 4500, false, false, 3),
            G(catPc, "Redmi Pad Pro 12.1", "2.5K大屏 10000mAh 骁龙8s",
              realPics[2], 349900, 3100, true, true, 4),
            // 配件周边
            G(catAcc, "小米蓝牙耳机 Buds 5 Pro", "55dB降噪 空间音频 28h续航",
              realPics[3], 69900, 18900, true, true, 1),
            G(catAcc, "小米充电宝 20000mAh 33W", "双向快充 PD3.0 数显",
              realPics[4], 14900, 89000, true, true, 2),
            G(catAcc, "小米氮化镓充电器 67W", "3口同时快充 折叠插脚",
              realPics[5], 14900, 34000, true, false, 3),
            G(catAcc, "小米SU7 车载充电器", "66W 超级闪充 快充线",
              realPics[6], 9900, 15600, false, false, 4),
            // 生活电器
            G(catAppl, "小米电饭煲 4L IH", "球釜内胆 智能烹饪 预约",
              realPics[7], 39900, 9800, true, false, 1),
            G(catAppl, "小米电吹风 H500", "9档风速 负离子 恒温护发",
              realPics[0], 19900, 15600, true, false, 2),
            G(catAppl, "米家净水器 1000G Pro", "RO反渗透 零陈水 智能龙头",
              realPics[1], 249900, 2100, true, true, 3),
        )
        val goodsIds = mutableListOf<Long>()
        goods.forEach { g ->
            val id = Goods.insert {
                it[Goods.typeId] = g.typeId
                it[Goods.title] = g.title
                it[Goods.subTitle] = g.subTitle
                it[Goods.mainPic] = g.mainPic
                it[Goods.price] = g.price
                it[Goods.sold] = g.sold
                it[Goods.recommend] = g.recommend
                it[Goods.featured] = g.featured
                it[Goods.status] = 1
                it[Goods.sortNum] = g.sort
                it[Goods.createTime] = now
                it[Goods.updateTime] = now
            } get Goods.id
            goodsIds.add(id)
        }

        // ===== 商品规格 =====
        data class Spec(val goodsIdx: Int, val spec: String, val priceDiff: Int, val stock: Int)
        val specs = listOf(
            // 小米15 Pro
            Spec(0, "黑色 12GB+256GB", 0, 500),
            Spec(0, "黑色 16GB+512GB", 60000, 300),
            Spec(0, "白色 12GB+256GB", 0, 400),
            Spec(0, "白色 16GB+512GB", 60000, 200),
            // 小米15
            Spec(1, "黑色 12GB+256GB", 0, 800),
            Spec(1, "金色 12GB+256GB", 0, 600),
            Spec(1, "16GB+512GB", 50000, 400),
            // Redmi K80 Pro
            Spec(2, "墨羽 12GB+256GB", 0, 1200),
            Spec(2, "16GB+1TB", 100000, 500),
            // Redmi K80
            Spec(3, "墨羽 12GB+256GB", 0, 2000),
            Spec(3, "16GB+512GB", 40000, 800),
            // 小米手表S3
            Spec(5, "曜石黑", 0, 300),
            Spec(5, "星云黑", 0, 200),
            // 小米手环9 NFC
            Spec(7, "曜石黑 NFC版", 0, 5000),
            Spec(7, "活力橙 NFC版", 0, 3000),
            // 小米充电宝
            Spec(14, "20000mAh 22.5W", 0, 20000),
            Spec(14, "20000mAh 45W", 3000, 10000),
            // 小米Buds 5 Pro
            Spec(16, "星空黑", 0, 2000),
            Spec(16, "月影白", 0, 1500),
        )
        specs.forEach { s ->
            val gid = goodsIds.getOrNull(s.goodsIdx) ?: return@forEach
            GoodsSpecs.insert {
                it[GoodsSpecs.goodsId] = gid
                it[GoodsSpecs.spec] = s.spec
                it[GoodsSpecs.price] = goods[s.goodsIdx].price + s.priceDiff
                it[GoodsSpecs.stock] = s.stock
            }
        }

        // ===== 优惠券 =====
        data class Cup(val name: String, val discount: Int, val minAmount: Int, val start: String, val end: String)
        val coupons = listOf(
            Cup("满500减50", 5000, 500, "2026-10-01", "2026-12-31"),
            Cup("满1000减100", 10000, 1000, "2026-10-01", "2026-12-31"),
            Cup("满3000减300", 30000, 3000, "2026-10-01", "2026-12-31"),
            Cup("新人专享满100减20", 1000, 200, "2026-10-01", "2026-12-31"),
        )
        coupons.forEach { c ->
            Coupons.insert {
                it[Coupons.name] = c.name
                it[Coupons.discount] = c.discount
                it[Coupons.minAmount] = c.minAmount
                it[Coupons.totalCount] = 10000
                it[Coupons.remainCount] = 10000
                it[Coupons.startTime] = c.start
                it[Coupons.endTime] = c.end
                it[Coupons.status] = 1
            }
        }

        // ===== 热门搜索关键词 =====
        val keywords = listOf(
            "小米15", "小米15 Pro", "Redmi K80", "小米SU7",
            "小米手表", "小米手环", "小米空气净化器", "小米电饭煲",
            "小米耳机", "小米充电宝", "小米笔记本", "小米扫地机器人",
        )
        keywords.forEachIndexed { idx, kw ->
            SearchKeywords.insert {
                it[SearchKeywords.keyword] = kw
                it[SearchKeywords.searchCount] = (10000 - idx * 500).coerceAtLeast(100)
                it[SearchKeywords.isHot] = idx < 6
                it[SearchKeywords.sortNum] = idx
            }
        }

        // ===== 测试用户 =====
        Users.insert {
            it[Users.phone] = "13917825125"
            it[Users.password] = "123456"
            it[Users.nickname] = "无表情"
            it[Users.status] = 1
            it[Users.createTime] = now
            it[Users.updateTime] = now
        }
    }
}

// ===== 数据表定义 =====

object Users : Table("t_user") {
    val id = long("id").autoIncrement()
    val phone = varchar("phone", 20).uniqueIndex()
    val password = varchar("password", 128)      // BCrypt 加密后
    val nickname = varchar("nickname", 64).nullable()
    val avatarUrl = varchar("avatar_url", 512).nullable()
    val status = integer("status").default(1)      // 0-禁用 1-正常
    val createTime = varchar("create_time", 32).default("")
    val updateTime = varchar("update_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}

object Addresses : Table("t_address") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").index()
    val receiver = varchar("receiver", 64)
    val phone = varchar("phone", 20)
    val province = varchar("province", 64)
    val city = varchar("city", 64)
    val district = varchar("district", 64)
    val detail = varchar("detail", 256)
    val isDefault = bool("is_default").default(false)
    override val primaryKey = PrimaryKey(id)
}

object Categories : Table("t_category") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 64)
    val parentId = long("parent_id").default(0)
    val pic = varchar("pic", 512).nullable()
    val sortNum = integer("sort_num").default(0)
    override val primaryKey = PrimaryKey(id)
}

object Goods : Table("t_goods") {
    val id = long("id").autoIncrement()
    val typeId = long("type_id").index()
    val title = varchar("title", 256)
    val subTitle = varchar("sub_title", 256).nullable()
    val mainPic = varchar("main_pic", 512)
    val pics = text("pics").nullable()                       // JSON 数组
    val price = integer("price")                              // 分
    val sold = integer("sold").default(0)
    val contentPics = text("content_pics").nullable()         // JSON 数组
    val recommend = bool("recommend").default(false)
    val featured = bool("featured").default(false)
    val status = integer("status").default(1)                 // 0-下架 1-上架
    val sortNum = integer("sort_num").default(0)
    val createTime = varchar("create_time", 32).default("")
    val updateTime = varchar("update_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}

object Orders : Table("t_order") {
    val id = long("id").autoIncrement()
    val orderNo = varchar("order_no", 32).uniqueIndex()
    val userId = long("user_id").index()
    val addressSnapshot = text("address_snapshot")           // 下单时地址快照 JSON
    val totalPrice = integer("total_price")
    val payPrice = integer("pay_price")
    val couponId = long("coupon_id").nullable()
    val status = integer("status").default(0)                 // 0-待支付 1-已支付 2-已发货 3-已签收 4-已取消 5-退款中 6-已退款
    val remark = varchar("remark", 256).default("")
    val payTime = varchar("pay_time", 32).nullable()
    val shipTime = varchar("ship_time", 32).nullable()
    val receiveTime = varchar("receive_time", 32).nullable()
    val createTime = varchar("create_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}

object OrderItems : Table("t_order_item") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id").index()
    val goodsId = long("goods_id")
    val goodsTitle = varchar("goods_title", 256)
    val goodsMainPic = varchar("goods_main_pic", 512)
    val specInfo = varchar("spec_info", 256).nullable()       // 规格描述
    val price = integer("price")
    val quantity = integer("quantity")
    override val primaryKey = PrimaryKey(id)
}

object Coupons : Table("t_coupon") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 128)
    val discount = integer("discount")                        // 减免金额（分）
    val minAmount = integer("min_amount").default(0)          // 最低消费
    val totalCount = integer("total_count").default(0)        // 发放总量
    val remainCount = integer("remain_count").default(0)      // 剩余
    val startTime = varchar("start_time", 32)
    val endTime = varchar("end_time", 32)
    val status = integer("status").default(1)
    override val primaryKey = PrimaryKey(id)
}

object UserCoupons : Table("t_user_coupon") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").index()
    val couponId = long("coupon_id").index()
    val status = integer("status").default(0)                 // 0-未使用 1-已使用 2-已过期
    val receiveTime = varchar("receive_time", 32).default("")
    val useTime = varchar("use_time", 32).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Collections : Table("t_collection") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").index()
    val goodsId = long("goods_id").index()
    val createTime = varchar("create_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}

object Notifications : Table("t_notification") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").index()
    val title = varchar("title", 128)
    val content = text("content")
    val type = integer("type").default(0)                     // 类型占位
    val isRead = bool("is_read").default(false)
    val createTime = varchar("create_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}

object Feedbacks : Table("t_feedback") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").index()
    val type = varchar("type", 64)
    val content = text("content")
    val images = text("images").nullable()                    // JSON 数组
    val reply = text("reply").nullable()
    val createTime = varchar("create_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}

// ===== 缺失的新表 =====

/** 首页轮播图 */
object Banners : Table("t_banner") {
    val id = long("id").autoIncrement()
    val pic = varchar("pic", 512)                             // 图片 URL
    val url = varchar("url", 512).nullable()                  // 点击跳转链接
    val title = varchar("title", 128).nullable()
    val sortNum = integer("sort_num").default(0)
    val status = integer("status").default(1)                 // 0-禁用 1-启用
    override val primaryKey = PrimaryKey(id)
}

/** 商品规格（如"白色/8+128GB"） */
object GoodsSpecs : Table("t_goods_spec") {
    val id = long("id").autoIncrement()
    val goodsId = long("goods_id").index()
    val spec = varchar("spec", 256)                           // 规格描述
    val price = integer("price")                              // 该规格价格（分）
    val stock = integer("stock").default(0)                   // 库存
    override val primaryKey = PrimaryKey(id)
}

/** 商品评论 */
object Comments : Table("t_comment") {
    val id = long("id").autoIncrement()
    val goodsId = long("goods_id").index()
    val userId = long("user_id").index()
    val orderId = long("order_id").nullable()
    val content = text("content")
    val images = text("images").nullable()                    // JSON 数组
    val createTime = varchar("create_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}

/** 购物车 */
object Cart : Table("t_cart") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").index()
    val goodsId = long("goods_id").index()
    val specId = long("spec_id").nullable()
    val quantity = integer("quantity").default(1)
    val isSelected = bool("is_selected").default(true)
    val createTime = varchar("create_time", 32).default("")
    val updateTime = varchar("update_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}

/** 热门搜索关键词 */
object SearchKeywords : Table("t_search_keyword") {
    val id = long("id").autoIncrement()
    val keyword = varchar("keyword", 64).uniqueIndex()
    val searchCount = integer("search_count").default(0)
    val isHot = bool("is_hot").default(false)
    val sortNum = integer("sort_num").default(0)
    override val primaryKey = PrimaryKey(id)
}
