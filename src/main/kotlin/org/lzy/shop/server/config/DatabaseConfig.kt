package org.lzy.shop.server.config

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.lzy.shop.server.config.DatabaseConfig.connect

/**
 * 数据库连接 + 建表
 *
 * 当前使用 H2 嵌入式数据库（文件模式），零安装，数据落在 ./data/shop 文件中。
 * 如果以后想换 MySQL / PostgreSQL，只改 [connect] 里的 URL 和 driver 即可。
 *
 * Exposed 是 Kotlin 官方出的 ORM，API 类型安全，比 JPA/Hibernate 轻量很多。
 */
object DatabaseConfig {

    /** 图片基础地址 —— 用户自定义格式：Nginx 直接静态服务，不走 Ktor */
    private const val IMG_BASE = "http://lzy.soubei1123.cn/app/food"

    /** 根据 slug 生成图片 URL，JPG 格式（供种子数据和其他配置复用） */
    fun foodPic(slug: String): String = "$IMG_BASE/$slug.jpg"

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
                SearchKeywords,
                Recipes,
                RecipeIngredients,
                RecipeSteps,
                RecipeCollections,
                BrowseHistory
            )
            // 每次启动无条件清理无效图片 URL（trae 内部 API、旧本地路径等）
            migrateBadImageUrls()
            // 首次启动灌入食品饮料类种子数据；若旧数据不是食品类则先清掉再灌
            seedIfEmpty()
        }
    }

    /**
     * 每次启动都无条件清理无效的图片 URL
     * 不管数据库是不是空的、不管要不要种种子，都跑一遍
     * 把 trae 内部 API URL、旧本地路径等全部替换成公网 picsum.photos
     */
    private fun migrateBadImageUrls() {
        // 1) 旧的 /app/images/xxx.jpg 本地路径 → picsum 直链
        val picMap = mapOf(
            "/app/images/cat-snack.jpg" to "https://picsum.photos/seed/cat-snack/600/600",
            "/app/images/cat-drink.jpg" to "https://picsum.photos/seed/cat-drink/600/600",
            "/app/images/cat-oil.jpg" to "https://picsum.photos/seed/cat-oil/600/600",
            "/app/images/cat-instant.jpg" to "https://picsum.photos/seed/cat-instant/600/600",
            "/app/images/cat-fresh.jpg" to "https://picsum.photos/seed/cat-fresh/600/600",
            "/app/images/cat-health.jpg" to "https://picsum.photos/seed/cat-health/600/600",
            "/app/images/cat-tea.jpg" to "https://picsum.photos/seed/cat-tea/600/600",
            "/app/images/cat-choco.jpg" to "https://picsum.photos/seed/cat-choco/600/600",
            "/app/images/goods-1.jpg" to "https://picsum.photos/seed/goods-1/600/600",
            "/app/images/goods-2.jpg" to "https://picsum.photos/seed/goods-2/600/600",
            "/app/images/goods-3.jpg" to "https://picsum.photos/seed/goods-3/600/600",
            "/app/images/goods-4.jpg" to "https://picsum.photos/seed/goods-4/600/600",
            "/app/images/goods-5.jpg" to "https://picsum.photos/seed/goods-5/600/600",
            "/app/images/goods-6.jpg" to "https://picsum.photos/seed/goods-6/600/600",
            "/app/images/goods-7.jpg" to "https://picsum.photos/seed/goods-7/600/600",
            "/app/images/goods-8.jpg" to "https://picsum.photos/seed/goods-8/600/600",
            "/app/images/goods-9.jpg" to "https://picsum.photos/seed/goods-9/600/600",
            "/app/images/goods-10.jpg" to "https://picsum.photos/seed/goods-10/600/600",
            "/app/images/banner-1.jpg" to "https://picsum.photos/seed/banner-1/1200/400",
            "/app/images/banner-2.jpg" to "https://picsum.photos/seed/banner-2/1200/400",
            "/app/images/banner-3.jpg" to "https://picsum.photos/seed/banner-3/1200/400",
            "/app/images/recipe-1.jpg" to "https://picsum.photos/seed/recipe-1/800/600",
            "/app/images/recipe-2.jpg" to "https://picsum.photos/seed/recipe-2/800/600",
            "/app/images/recipe-3.jpg" to "https://picsum.photos/seed/recipe-3/800/600",
            "/app/images/recipe-4.jpg" to "https://picsum.photos/seed/recipe-4/800/600",
            "/app/images/recipe-5.jpg" to "https://picsum.photos/seed/recipe-5/800/600",
            "/app/images/recipe-6.jpg" to "https://picsum.photos/seed/recipe-6/800/600",
            "/app/images/recipe-7.jpg" to "https://picsum.photos/seed/recipe-7/800/600",
            "/app/images/recipe-8.jpg" to "https://picsum.photos/seed/recipe-8/800/600",
        )
        picMap.forEach { (old, newUrl) ->
            Categories.update({ Categories.pic eq old }) { it[Categories.pic] = newUrl }
            Goods.update({ Goods.mainPic eq old }) { it[Goods.mainPic] = newUrl }
            Banners.update({ Banners.pic eq old }) { it[Banners.pic] = newUrl }
            Recipes.update({ Recipes.pic eq old }) { it[Recipes.pic] = newUrl }
        }

        // 2) trae-api-cn.mchost.guru 内部 API URL → picsum.photos 可访问 URL
        //    只要包含 trae-api-cn.mchost.guru 就替换，不管什么格式
        val traeRegex = Regex("""https://trae-api-cn\.mchost\.guru/api/ide/v1/text_to_image\?prompt=([^&]+)""")
        fun migrateTrae(url: String): String {
            val m = traeRegex.find(url) ?: return "https://picsum.photos/seed/fallback/600/600"
            val prompt = m.groupValues[1]
            return "https://picsum.photos/seed/$prompt/600/600"
        }
        Categories.selectAll().forEach { row ->
            val old = row[Categories.pic] ?: ""
            if (old.contains("trae-api-cn.mchost.guru"))
                Categories.update({ Categories.id eq row[Categories.id] }) { it[Categories.pic] = migrateTrae(old) }
        }
        Goods.selectAll().forEach { row ->
            val old = row[Goods.mainPic]
            if (old.contains("trae-api-cn.mchost.guru"))
                Goods.update({ Goods.id eq row[Goods.id] }) { it[Goods.mainPic] = migrateTrae(old) }
        }
        Banners.selectAll().forEach { row ->
            val old = row[Banners.pic]
            if (old.contains("trae-api-cn.mchost.guru"))
                Banners.update({ Banners.id eq row[Banners.id] }) { it[Banners.pic] = migrateTrae(old) }
        }
        Recipes.selectAll().forEach { row ->
            val old = row[Recipes.pic]
            if (old.contains("trae-api-cn.mchost.guru"))
                Recipes.update({ Recipes.id eq row[Recipes.id] }) { it[Recipes.pic] = migrateTrae(old) }
        }
        RecipeSteps.selectAll().forEach { row ->
            val old = row[RecipeSteps.pic] ?: ""
            if (old.contains("trae-api-cn.mchost.guru"))
                RecipeSteps.update({ RecipeSteps.id eq row[RecipeSteps.id] }) { it[RecipeSteps.pic] = migrateTrae(old) }
        }

        // 3) 菜谱 /app/images/ → /frontend/images/（与 Ktor 静态资源挂载对齐）
        Recipes.selectAll().forEach { row ->
            val old = row[Recipes.pic]
            if (old.startsWith("/app/images/"))
                Recipes.update({ Recipes.id eq row[Recipes.id] }) { it[Recipes.pic] = old.replaceFirst("/app/images/", "/frontend/images/") }
        }
        RecipeSteps.selectAll().forEach { row ->
            val old = row[RecipeSteps.pic] ?: ""
            if (old.startsWith("/app/images/"))
                RecipeSteps.update({ RecipeSteps.id eq row[RecipeSteps.id] }) { it[RecipeSteps.pic] = old.replaceFirst("/app/images/", "/frontend/images/") }
        }
    }

    /**
     * 种子数据 —— 食品饮料类商品、分类、轮播图、优惠券、测试用户 + 假订单
     *
     * 如果已存在的分类不是食品类（比如小米生态旧数据），会先清空所有业务表再重新灌入。
     * 保证每次拿到的都是一致的食品商城测试数据。
     */
    private fun seedIfEmpty() {
        val existingCats = Categories.selectAll().map { it[Categories.name] }.toSet()

        // 检测是否需要重置：如果分类里有明显的非食品类，就全量清掉重灌
        val needReset = existingCats.isNotEmpty() &&
            existingCats.none { it.contains("零食") || it.contains("饮料") || it.contains("粮油") }

        if (needReset) {
            // 按外键依赖顺序清表
            OrderItems.deleteAll()
            Orders.deleteAll()
            GoodsSpecs.deleteAll()
            Goods.deleteAll()
            Banners.deleteAll()
            SearchKeywords.deleteAll()
            Coupons.deleteAll()
            UserCoupons.deleteAll()
            Addresses.deleteAll()
            Cart.deleteAll()
            Collections.deleteAll()
            Notifications.deleteAll()
            Feedbacks.deleteAll()
            Comments.deleteAll()
            Users.deleteAll()
            Categories.deleteAll()
        }

        // ===== 迁移逻辑已移至 connect() 里的 migrateBadImageUrls()，每次启动无条件执行 =====

        // 只要核心表任一为空就全量种种子（避免 Categories 有数据但 Recipes/Goods 没种的情况）
        // 另外：如果菜谱少于 30 条，也强制重种（说明是旧种子数据）
        val recipesCount = Recipes.selectAll().count()
        val needSeed = listOf(
            Categories.selectAll().count(),
            Goods.selectAll().count(),
            Banners.selectAll().count(),
            Coupons.selectAll().count(),
            Recipes.selectAll().count(),
        ).any { it == 0L } || recipesCount < 30

        if (recipesCount in 1..29) {
            // 旧菜谱太少，先清掉所有菜谱相关数据再重种
            RecipeCollections.deleteAll()
            RecipeIngredients.deleteAll()
            RecipeSteps.deleteAll()
            Recipes.deleteAll()
        }

        if (!needSeed) return

        // 需要全量重种 —— 先清干净所有业务表（避免旧数据和新种子并存/重复）
        OrderItems.deleteAll()
        Orders.deleteAll()
        GoodsSpecs.deleteAll()
        Goods.deleteAll()
        Banners.deleteAll()
        SearchKeywords.deleteAll()
        Coupons.deleteAll()
        UserCoupons.deleteAll()
        Addresses.deleteAll()
        Cart.deleteAll()
        Collections.deleteAll()
        Notifications.deleteAll()
        Feedbacks.deleteAll()
        Comments.deleteAll()
        RecipeCollections.deleteAll()
        RecipeIngredients.deleteAll()
        RecipeSteps.deleteAll()
        Recipes.deleteAll()
        Categories.deleteAll()

        val now = System.currentTimeMillis().toString()

        // ===== 分类 =====
        data class Cat(val name: String, val parentId: Long, val sort: Int, val slug: String)
        val categories = listOf(
            Cat("休闲零食", 0, 1, "cat_snack"),
            Cat("饮料冲调", 0, 2, "cat_drink"),
            Cat("粮油调味", 0, 3, "cat_oil"),
            Cat("方便速食", 0, 4, "cat_instant"),
            Cat("生鲜果蔬", 0, 5, "cat_fresh"),
            Cat("保健食品", 0, 6, "cat_health"),
            Cat("茗茶酒水", 0, 7, "cat_tea"),
            Cat("糖巧礼盒", 0, 8, "cat_choco"),
        )
        val catIds = mutableListOf<Long>()

        categories.forEach { c ->
            val id = Categories.insert {
                it[Categories.name] = c.name
                it[Categories.parentId] = c.parentId
                it[Categories.sortNum] = c.sort
                it[Categories.pic] = foodPic(c.slug)
            } get Categories.id
            catIds.add(id)
        }
        val catSnack   = catIds[0]
        val catDrink   = catIds[1]
        val catOil     = catIds[2]
        val catInstant = catIds[3]
        val catFresh   = catIds[4]
        val catHealth  = catIds[5]
        val catTea     = catIds[6]
        val catChoco   = catIds[7]

        // ===== 轮播图 =====
        data class BannerImg(val slug: String, val title: String, val sort: Int)
        val banners = listOf(
            BannerImg("banner_snack_promo", "乐事薯片 狂欢促销 限时满减", 1),
            BannerImg("banner_water_bulk", "农夫山泉 囤货季 全屋用水", 2),
            BannerImg("banner_nutrition_breakfast", "三只松鼠 每日坚果 营养早餐", 3),
        )
        banners.forEach { b ->
            Banners.insert {
                it[Banners.pic] = foodPic(b.slug)
                it[Banners.url] = ""
                it[Banners.title] = b.title
                it[Banners.sortNum] = b.sort
                it[Banners.status] = 1
            }
        }

        // ===== 商品（60 个真实食品饮料商品） =====
        data class G(
            val typeId: Long, val title: String, val subTitle: String,
            val mainPic: String, val price: Int, val sold: Int,
            val recommend: Boolean, val featured: Boolean, val sort: Int
        )
        val goods = listOf(
            // —— 休闲零食 —— (10条)
            G(catSnack, "乐事无限薯片 多口味104g*9罐", "百事食品 膨化零食 休闲小吃", foodPic("lays_chips"), 2690, 58000, true, true, 1),
            G(catSnack, "三只松鼠 每日坚果30包 750g", "混合干果仁 孕妇儿童营养 量贩装", foodPic("three_squirrels_nuts"), 7990, 42000, true, true, 2),
            G(catSnack, "良品铺子 夏威夷果500g 罐装", "大开口奶香味 每日坚果 送礼首选", foodPic("hawaiian_nuts"), 2990, 31000, true, false, 3),
            G(catSnack, "洽洽香瓜子260g", "经典原味 炒货休闲零食 当季新籽", foodPic("qiaqia_sunflower_seeds"), 990, 89000, true, false, 4),
            G(catSnack, "卫龙大面筋辣条102g", "经典童年辣条 麻辣鲜香 零食小吃", foodPic("weilong_spicy_gluten"), 390, 156000, true, true, 5),
            G(catSnack, "良品铺子 手撕面包1000g", "整箱装 软面包早餐蛋糕 休闲零食", foodPic("hand_torn_bread"), 1990, 67000, true, false, 6),
            G(catSnack, "三只松鼠 芒果干116g", "泰国风味水果干 蜜饯果脯 休闲零食", foodPic("mango_dried"), 990, 45000, false, false, 7),
            G(catSnack, "良品铺子 猪肉脯150g", "靖江特产 原味肉干 休闲零食小吃", foodPic("pork_dried"), 2990, 28000, true, false, 8),
            G(catSnack, "百草味 碧根果218g", "美国山核桃 每日坚果炒货 休闲零食", foodPic("pecan_nuts"), 3290, 19000, false, false, 9),
            G(catSnack, "乐事薯片原味104g", "经典原味 薯片 膨化食品", foodPic("lays_chips_original"), 790, 98000, true, true, 10),

            // —— 饮料冲调 —— (12条)
            G(catDrink, "农夫山泉 饮用水550ml*24瓶", "天然水 整箱量贩 办公家庭囤货", foodPic("nongfu_spring_water"), 3390, 189000, true, true, 1),
            G(catDrink, "可口可乐 经典口味汽水 330ml*24罐", "碳酸饮料 整箱装", foodPic("coca_cola"), 4990, 145000, true, true, 2),
            G(catDrink, "可口可乐 零度无糖 330ml*24罐", "零度可乐 无糖畅爽 整箱", foodPic("coke_zero"), 4990, 98000, true, false, 3),
            G(catDrink, "百岁山 矿泉水570ml*24瓶", "景田 天然矿泉水 高端饮用水", foodPic("cestbon_water"), 5990, 56000, true, false, 4),
            G(catDrink, "红牛 功能饮料250ml*24罐", "能量饮料 维生素功能 提神抗疲劳", foodPic("red_bull"), 11990, 78000, true, true, 5),
            G(catDrink, "康师傅冰红茶500ml*15瓶", "柠檬味红茶饮料 整箱装", foodPic("master_kong_iced_tea"), 3490, 123000, true, false, 6),
            G(catDrink, "蒙牛特仑苏 纯牛奶250ml*16盒", "全脂灭菌乳 高端牛奶礼盒", foodPic("mengniu_telunshu"), 6990, 92000, true, true, 7),
            G(catDrink, "伊利金典 纯牛奶250ml*16盒", "有机奶 全脂牛奶 高端礼盒", foodPic("yili_jindian_milk"), 6690, 76000, true, false, 8),
            G(catDrink, "瑞幸 生椰拿铁即饮咖啡300ml*6瓶", "椰香咖啡 即饮饮料 冷藏咖啡", foodPic("luckin_coconut_latte"), 2990, 34000, true, false, 9),
            G(catDrink, "雀巢 速溶咖啡1+2原味15g*100条", "即溶咖啡 办公提神 量贩装", foodPic("nescafe_inst_coffee"), 9900, 45000, true, true, 10),
            G(catDrink, "三得利 乌龙茶500ml*15瓶", "无糖乌龙茶 茶饮料 整箱", foodPic("suntory_oolong_tea"), 3990, 28000, false, false, 11),
            G(catDrink, "东鹏特饮 功能饮料250ml*24罐", "维生素功能饮料 提神抗疲劳", foodPic("dongpeng_teyin"), 7990, 56000, true, false, 12),

            // —— 粮油调味 —— (7条)
            G(catOil, "圣上用膳 五常大米10斤", "GB/T 19266 东北五常 2026新米", foodPic("wuchang_rice"), 4990, 500000, true, true, 1),
            G(catOil, "十月稻田 东北长粒香大米10斤", "黑龙江产地 粳米 真空包装", foodPic("long_grain_rice"), 4490, 89000, true, false, 2),
            G(catOil, "金龙鱼 菜籽油5L", "非转基因 压榨食用油 家用桶装", foodPic("golden_dragon_fish_oil"), 6990, 67000, true, false, 3),
            G(catOil, "鲁花 花生油5L", "5S物理压榨 非转基因 食用油", foodPic("luhua_peanut_oil"), 10990, 45000, true, true, 4),
            G(catOil, "海天 味极鲜生抽1.28L", "特级生抽 酿造酱油 厨房调味品", foodPic("haitian_light_soy"), 1990, 78000, true, false, 5),
            G(catOil, "王守义 十三香45g", "经典香辛料 厨房调味料 中华老字号", foodPic("wangshouyi_13_spices"), 590, 234000, true, true, 6),
            G(catOil, "六月鲜 红烧酱油1L", "特级老抽 红烧上色 酿造酱油", foodPic("liuyuexian_dark_soy"), 1290, 34000, false, false, 7),

            // —— 方便速食 —— (7条)
            G(catInstant, "康师傅 红烧牛肉面100g*24包", "经典口味 速食方便面 整箱", foodPic("master_kong_beef_noodle"), 3990, 345000, true, true, 1),
            G(catInstant, "统一 老坛酸菜牛肉面121g*24包", "酸菜味 速食方便泡面 整箱", foodPic("uni_pickled_cabbage_noodle"), 4190, 298000, true, false, 2),
            G(catInstant, "自嗨锅 麻辣牛肉自热火锅", "自加热 方便速食 网红小火锅", foodPic("zihaiguo_spicy_beef_hotpot"), 1990, 89000, true, true, 3),
            G(catInstant, "海底捞 番茄牛肉自热火锅", "自煮火锅 番茄汤底 懒人速食", foodPic("haidilao_tomato_hotpot"), 3990, 56000, true, false, 4),
            G(catInstant, "正大食品 玉米蔬菜猪肉水饺500g", "速冻水饺 早餐速食 家庭装", foodPic("cp_pork_dumplings"), 2990, 45000, false, false, 5),
            G(catInstant, "梅林 午餐肉罐头340g", "经典午餐肉 涮火锅 即食罐头", foodPic("meilin_spam"), 990, 123000, true, false, 6),
            G(catInstant, "安佳 黄油454g", "进口黄油 烘焙原料 无盐黄油块", foodPic("anchor_butter"), 4990, 23000, false, false, 7),

            // —— 生鲜果蔬（当季食材，25 条）——
            G(catFresh, "红富士苹果5斤装", "山东烟台 新鲜水果 脆甜多汁", foodPic("red_fuji_apple"), 1990, 156000, true, false, 1),
            G(catFresh, "赣南脐橙5斤装", "江西赣州 新鲜橙子 应季水果", foodPic("navel_orange"), 2990, 134000, true, true, 2),
            G(catFresh, "智利车厘子J级2kg", "进口大樱桃 新鲜水果 礼盒装", foodPic("chilean_cherries"), 8990, 78000, true, true, 3),
            G(catFresh, "海南香蕉5斤装", "新鲜热带水果 当季直发", foodPic("hainan_banana"), 1290, 98000, false, false, 4),
            G(catFresh, "山东大葱500g", "新鲜蔬菜 葱白粗壮 生吃卷饼", foodPic("shandong_scallion"), 490, 234000, true, false, 5),
            G(catFresh, "有机西红柿500g", "有机认证 新鲜蔬菜 沙瓤番茄", foodPic("organic_tomato"), 990, 67000, false, false, 6),
            G(catFresh, "山东黄瓜500g", "新鲜蔬菜 脆嫩爽口 生吃凉拌", foodPic("shandong_cucumber"), 450, 189000, false, false, 7),
            G(catFresh, "丹东草莓500g", "新鲜草莓 应季水果 产地直发", foodPic("dandong_strawberry"), 2590, 89000, true, false, 8),
            G(catFresh, "云南蓝莓125g*4盒", "高海拔蓝莓 花青素丰富", foodPic("yunnan_blueberry"), 4990, 56000, true, false, 9),
            G(catFresh, "陕西猕猴桃2.5斤", "绿心猕猴桃 应季水果", foodPic("shaanxi_kiwi"), 1890, 123000, true, false, 10),
            G(catFresh, "进口牛油果6个", "墨西哥牛油果 沙拉健身", foodPic("avocado"), 4590, 45000, true, false, 11),
            G(catFresh, "山东大蒜500g", "新鲜大蒜头 厨房必备", foodPic("shandong_garlic"), 690, 312000, false, false, 12),
            G(catFresh, "云南小黄姜500g", "新鲜生姜 味浓香足", foodPic("yunnan_ginger"), 790, 198000, false, false, 13),
            G(catFresh, "山东胡萝卜500g", "新鲜胡萝卜 脆甜多汁", foodPic("shandong_carrot"), 590, 156000, false, false, 14),
            G(catFresh, "有机紫皮洋葱500g", "紫皮洋葱 辛辣浓郁", foodPic("red_onion"), 690, 187000, false, false, 15),
            G(catFresh, "山东土豆10斤", "荷兰十五土豆 淀粉含量高", foodPic("shandong_potato"), 2990, 267000, false, false, 16),
            G(catFresh, "江西茄子500g", "新鲜茄子 紫皮长茄", foodPic("jiangxi_eggplant"), 490, 145000, false, false, 17),
            G(catFresh, "山东青椒500g", "新鲜青椒 肉厚脆爽", foodPic("shandong_bell_pepper"), 590, 178000, false, false, 18),
            G(catFresh, "四川菠菜500g", "新鲜菠菜 叶嫩汁多", foodPic("sichuan_spinach"), 550, 165000, false, false, 19),
            G(catFresh, "云南西兰花500g", "青花菜 营养丰富", foodPic("yunnan_broccoli"), 790, 134000, false, false, 20),
            G(catFresh, "东北甜玉米4根", "新鲜玉米 香甜软糯", foodPic("northeast_sweet_corn"), 1290, 112000, true, false, 21),
            G(catFresh, "山东红薯2.5斤", "板栗红薯 粉糯香甜", foodPic("shandong_sweet_potato"), 1490, 98000, false, false, 22),
            G(catFresh, "海南芒果3斤", "台农芒果 甜蜜多汁", foodPic("hainan_mango"), 2590, 87000, true, false, 23),
            G(catFresh, "广东柠檬1斤", "新鲜黄柠檬 维C丰富", foodPic("guangdong_lemon"), 890, 76000, false, false, 24),
            G(catFresh, "山东紫薯2斤", "紫薯 花青素之王", foodPic("shandong_purple_sweet_potato"), 1590, 65000, false, false, 25),

            // —— 保健食品 —— (4条)
            G(catHealth, "汤臣倍健 蛋白粉150g*3罐", "双蛋白 营养补充 成人健身", foodPic("byhealth_protein"), 19990, 34000, true, true, 1),
            G(catHealth, "同仁堂 西洋参含片100g", "滋补养生 中老年保健 同仁堂品质", foodPic("tongrentang_ginseng"), 8990, 12000, true, false, 2),
            G(catHealth, "Swisse斯维诗 维生素C泡腾片", "VC补充 免疫力 橙子味", foodPic("swisse_vitamin_c"), 8990, 28000, true, false, 3),
            G(catHealth, "康恩贝 蛋白粉450g", "植物蛋白 营养补充 家庭装", foodPic("conba_protein"), 6990, 19000, false, false, 4),

            // —— 茗茶酒水 —— (8条)
            G(catTea, "竹叶青 茶叶绿茶250g", "峨眉高山绿茶 明前特级 礼盒装", foodPic("zhuyeqing_green_tea"), 14990, 15000, true, false, 1),
            G(catTea, "云南普洱 熟茶饼357g", "勐海产区 普洱熟茶饼 陈香浓郁", foodPic("yunnan_puer_tea"), 8990, 23000, true, false, 2),
            G(catTea, "五粮液52度500ml", "浓香型白酒 八大名酒 宴请送礼", foodPic("wuliangye_liquor"), 139900, 34000, true, true, 3),
            G(catTea, "牛栏山陈酿52度500ml*12瓶", "浓香型白酒 整箱量贩 日常口粮", foodPic("niulanshan_liquor"), 89900, 12000, true, false, 4),
            G(catTea, "青岛啤酒 经典500ml*12罐", "拉格黄啤酒 整箱量贩 经典款", foodPic("tsingtao_beer"), 4990, 234000, true, true, 5),
            G(catTea, "科罗娜 啤酒330ml*12瓶", "墨西哥进口 拉格啤酒 聚会小酌", foodPic("corona_beer"), 8990, 45000, true, false, 6),
            G(catTea, "奔富Bin389 干红葡萄酒750ml", "澳洲进口 赤霞珠西拉 经典款", foodPic("penfolds_bin389"), 29990, 18000, true, true, 7),
            G(catTea, "路易拉菲 干红葡萄酒750ml", "法国进口 波尔多AOC 入门红酒", foodPic("louis_lafite_wine"), 16990, 15000, false, false, 8),

            // —— 糖巧礼盒 —— (5条)
            G(catChoco, "费列罗 榛果牛奶巧克力375g", "意大利进口 金球巧克力 礼盒装", foodPic("ferrero_rocher"), 8990, 56000, true, true, 1),
            G(catChoco, "德芙 巧克力礼盒280g", "丝滑牛奶巧克力 礼盒装 送礼", foodPic("dove_chocolate"), 4990, 34000, true, false, 2),
            G(catChoco, "好时 水滴巧克力500g", "美国进口 牛奶巧克力 散装量贩", foodPic("hersheys_kisses"), 5990, 28000, true, false, 3),
            G(catChoco, "大白兔奶糖500g", "上海特产 经典奶糖 童年回忆", foodPic("white_rabbit_candy"), 1990, 67000, true, true, 4),
            G(catChoco, "徐福记 酥心糖500g", "传统糖果 花生酥心糖 婚庆喜糖", foodPic("xufuji_candy"), 2490, 45000, false, false, 5),
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
            Spec(0,  "多口味组合装 104g*9罐", 0, 20000),
            Spec(0,  "原味 104g*9罐", 0, 15000),
            Spec(1,  "30包 750g 经典装", 0, 10000),
            Spec(1,  "30包 750g 益生菌款", 0, 8000),
            Spec(10, "550ml*24瓶 普通箱", 0, 50000),
            Spec(10, "550ml*24瓶 纸箱装", 200, 30000),
            Spec(11, "330ml*24罐", 0, 80000),
            Spec(11, "零度无糖 330ml*24罐", 0, 40000),
            Spec(14, "250ml*24罐 经典款", 0, 20000),
            Spec(14, "250ml*24罐 无糖款", 0, 15000),
            Spec(22, "10斤装 真空包装", 0, 100000),
            Spec(22, "20斤装 家庭装", 4990, 50000),
            Spec(24, "5L 桶装", 0, 10000),
            Spec(24, "5L*2 双桶礼盒", -1000, 5000),
            Spec(27, "100g*24包 整箱", 0, 200000),
            Spec(27, "100g*5包 五连包", 0, 500000),
            Spec(29, "番茄牛肉 自热火锅", 0, 30000),
            Spec(29, "麻辣嫩牛 自热火锅", 0, 25000),
            Spec(35, "J级 2kg装", 0, 20000),
            Spec(35, "JJ级 2kg装", 5000, 15000),
            Spec(43, "52度 500ml 单瓶", 0, 5000),
            Spec(43, "52度 500ml*6 整箱", 0, 2000),
            Spec(47, "750ml 单瓶", 0, 8000),
            Spec(47, "750ml*6 整箱", 0, 3000),
            Spec(50, "375g 礼盒装", 0, 15000),
            Spec(50, "96粒 婚礼装", 15000, 5000),
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
            Cup("满99减10 全场通用", 1000, 99, "2026-10-01", "2026-12-31"),
            Cup("满199减25 零食专享", 2500, 199, "2026-10-01", "2026-12-31"),
            Cup("满499减80 酒水专享", 8000, 499, "2026-10-01", "2026-12-31"),
            Cup("新人专享满50减10", 1000, 50, "2026-10-01", "2026-12-31"),
            Cup("满999减150 全品类", 15000, 999, "2026-10-01", "2026-12-31"),
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
            "乐事薯片", "三只松鼠", "农夫山泉", "可口可乐",
            "红牛", "五常大米", "鲁花花生油", "康师傅方便面",
            "海底捞自热火锅", "智利车厘子", "五粮液", "青岛啤酒",
            "奔富红酒", "费列罗巧克力", "德芙巧克力", "卫龙辣条",
        )
        keywords.forEachIndexed { idx, kw ->
            SearchKeywords.insert {
                it[SearchKeywords.keyword] = kw
                it[SearchKeywords.searchCount] = (20000 - idx * 600).coerceAtLeast(100)
                it[SearchKeywords.isHot] = idx < 8
                it[SearchKeywords.sortNum] = idx
            }
        }

        // ===== 测试用户（只留 1 个，其他由真实 App 注册产生）=====
        val testUsers = listOf(
            Triple("13917825125", "吃货小哥", "123456"),
        )
        val userIds = mutableListOf<Long>()
        testUsers.forEach { (phone, nickname, pwd) ->
            val id = Users.insert {
                it[Users.phone] = phone
                it[Users.password] = pwd
                it[Users.nickname] = nickname
                it[Users.status] = 1
                it[Users.createTime] = now
                it[Users.updateTime] = now
            } get Users.id
            userIds.add(id)
        }

        // ===== 假订单：删掉，订单由 App 真实下单产生（种子数据留空）=====
        // 以后要恢复测试订单，把下面注释解开即可
        // val orderStatuses = listOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 0)
        // ... (30 条订单种子代码)

        // ===== 菜谱种子数据 =====
        // 使用独立文件 RecipeSeedData.kt 维护 40 道菜谱的完整数据
        // 图片通过 text_to_image API 动态生成真实美食图
        RecipeSeedData.all.forEach { r ->
            val rid = Recipes.insert {
                it[Recipes.name] = r.name
                it[Recipes.pic] = r.pic
                it[Recipes.description] = r.desc
                it[Recipes.category] = r.cat
                it[Recipes.difficulty] = r.diff
                it[Recipes.time] = r.time
                it[Recipes.servings] = r.servings
                it[Recipes.author] = r.author
                it[Recipes.status] = 1
                it[Recipes.sortNum] = r.sort
                it[Recipes.createTime] = now
            } get Recipes.id

            // 食材
            r.ingredients.forEachIndexed { ingIdx, item ->
                val (name, qty) = item.split("→").let {
                    if (it.size == 2) it[0] to it[1] else it[0] to "适量"
                }
                RecipeIngredients.insert {
                    it[RecipeIngredients.recipeId] = rid
                    it[RecipeIngredients.ingredientName] = name
                    it[RecipeIngredients.quantity] = qty
                    it[RecipeIngredients.sortNum] = ingIdx
                }
            }

            // 做法步骤（配图：最后一步 = 成品主图，其他 = 通用步骤图）
            val totalSteps = r.steps.size
            r.steps.forEachIndexed { stepIdx, content ->
                RecipeSteps.insert {
                    it[RecipeSteps.recipeId] = rid
                    it[RecipeSteps.stepNo] = stepIdx + 1
                    it[RecipeSteps.content] = content
                    it[RecipeSteps.pic] = RecipeSeedData.stepImage(stepIdx, totalSteps, r.pic)
                    it[RecipeSteps.sortNum] = stepIdx
                }
            }
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

/** 菜谱 —— 菜谱Tab核心表 */
object Recipes : Table("t_recipe") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 64)                 // 菜名：如"番茄炒蛋"
    val pic = varchar("pic", 512)                  // 菜谱封面图
    val description = text("description").nullable() // 做法概述
    val category = varchar("category", 32)         // 分类：家常菜/快手菜/烘焙/汤品
    val difficulty = integer("difficulty").default(1) // 难度 1简单 2中等 3困难
    val time = integer("time").default(15)          // 耗时（分钟）
    val servings = integer("servings").default(2)   // 份量：几人份
    val author = varchar("author", 64).nullable()    // 作者/来源：如"美食厨娘"
    val status = integer("status").default(1)       // 0-下架 1-上架
    val sortNum = integer("sort_num").default(0)
    val createTime = varchar("create_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}

/** 菜谱做法步骤 —— 结构化的步骤数据，按序号排序 */
object RecipeSteps : Table("t_recipe_step") {
    val id = long("id").autoIncrement()
    val recipeId = long("recipe_id").index()
    val stepNo = integer("step_no")                 // 步骤序号 1,2,3...
    val content = text("content")                   // 步骤描述文字
    val pic = varchar("pic", 512).nullable()        // 步骤配图（可空）
    val sortNum = integer("sort_num").default(0)
    override val primaryKey = PrimaryKey(id)
}

/** 菜谱收藏 —— 用户收藏菜谱（区别于商品收藏Collections表） */
object RecipeCollections : Table("t_recipe_collection") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").index()
    val recipeId = long("recipe_id").index()
    val createTime = varchar("create_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}

/** 菜谱食材关联 —— 一道菜需要哪些商品 */
object RecipeIngredients : Table("t_recipe_ingredient") {
    val id = long("id").autoIncrement()
    val recipeId = long("recipe_id").index()
    val goodsId = long("goods_id").nullable()          // 关联 Goods 表（可空，种子数据先不关联）
    val ingredientName = varchar("ingredient_name", 64) // 食材名：如"番茄"
    val quantity = varchar("quantity", 32)           // 用量：如"2个"
    val sortNum = integer("sort_num").default(0)
    override val primaryKey = PrimaryKey(id)
}

/** 浏览历史 —— 用户浏览菜谱的记录，用于"浏览足迹" */
object BrowseHistory : Table("t_browse_history") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").index()
    val recipeId = long("recipe_id").index()
    val createTime = varchar("create_time", 32).default("")
    override val primaryKey = PrimaryKey(id)
}
