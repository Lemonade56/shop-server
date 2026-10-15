# ShopServer —— 电商 App 后端（Ktor + Kotlin）

与 Shop Android 客户端配套的后端服务，基于 Ktor 3.5 + Kotlin 2.0 构建。

## ✨ 特性

- 🚀 **Ktor 异步框架**：Kotlin 原生，与 App 用同一种语言
- 🗄️ **零配置数据库**：H2 嵌入式文件数据库，首次启动自动建表
- 🔐 **JWT 认证**：与 App 端 `TokenInterceptor` 100% 对齐（`Authorization` header，无 Bearer 前缀）
- 📦 **统一响应格式**：`{ code: 1000, message, data }`，与 App 端 `BaseResponse` 完全一致
- 📱 **11 个 API 模块**：用户、首页、商品、订单、地址、优惠券、收藏、通知、反馈、客服、上传
- 🧪 **Mock 数据**：所有接口已返回可消费的 JSON，App 切过来就能跑

## 📂 项目结构

```
ShopServer/
├── build.gradle.kts              # 单模块构建脚本
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/                # Gradle 8.10.2
├── src/main/kotlin/org/lzy/shop/server/
│   ├── Application.kt            # 启动入口 + 插件配置 + 路由挂载
│   ├── common/
│   │   └── BaseResponse.kt       # 统一响应格式 + ResultCode
│   ├── config/
│   │   ├── AppConfig.kt          # 端口/JWT/数据库配置
│   │   └── DatabaseConfig.kt     # 数据库连接 + 12 张表定义
│   ├── auth/
│   │   ├── JwtAuth.kt            # Ktor JWT 插件配置
│   │   └── JwtService.kt         # JWT 生成/解析工具
│   └── route/                    # 11 个 API 路由模块
│       ├── UserRoute.kt          # 用户/认证
│       ├── HomeRoute.kt          # 首页
│       ├── GoodsRoute.kt         # 商品/搜索
│       ├── OrderRoute.kt         # 订单/支付
│       ├── AddressRoute.kt       # 收货地址
│       ├── CouponRoute.kt        # 优惠券
│       ├── CollectionRoute.kt    # 商品收藏
│       ├── NotificationRoute.kt  # 消息通知
│       ├── FeedbackRoute.kt      # 用户反馈
│       ├── CustomerServiceRoute.kt # 客服消息
│       └── UploadRoute.kt        # 文件上传凭证
└── src/main/resources/
    └── application.conf         # Ktor 运行时配置（可选）
```

## 🚀 快速开始

### 1. 前置条件

- JDK 17+（推荐 Android Studio 自带的 JetBrains Runtime）
- Gradle Wrapper 会自动下载 Gradle 8.10.2

### 2. 启动服务

```bash
# Windows
.\gradlew.bat run

# 或者打 fat jar
.\gradlew.bat jar
java -jar build/libs/ShopServer-0.1.0.jar
```

启动成功后会看到：
```
[main] INFO  Application - No ktor.application.conf file found.
[main] INFO  ktor.application - Responding at http://0.0.0.0:8080
```

### 3. 测试接口

```bash
# 健康检查
curl http://localhost:8080/health
# → {"data":"ok","code":1000,"message":""}

# 获取首页
curl http://localhost:8080/app/page/home

# 用户注册
curl -X POST http://localhost:8080/app/user/login/register \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","password":"123456"}'

# 密码登录
curl -X POST http://localhost:8080/app/user/login/password \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","password":"123456"}'

# 访问需登录的接口（带上一步拿到的 token）
curl http://localhost:8080/app/user/info/person \
  -H "Authorization: <你的token>"
```

### 4. 让 App 连接这个后端

修改 App 的 `local.properties`：
```properties
debug.base.url=http://<你电脑的局域网IP>:8080/
release.base.url=http://<你电脑的局域网IP>:8080/
```

然后在 AndroidManifest.xml 里确保 debug 模式允许明文 HTTP：
```xml
<application android:usesCleartextTraffic="true" ...>
```

真机和后端要在同一个 WiFi 下。

## 🛠️ 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Kotlin | 2.0.21 | 主语言 |
| Ktor Server | 3.5.2 | HTTP 服务器 |
| kotlinx.serialization | 2.0.21 | JSON 序列化（与 App 共用） |
| auth0/java-jwt | 4.4.0 | JWT 生成与解析 |
| Exposed | 0.56.2 | Kotlin ORM |
| H2 | 2.3.232 | 嵌入式文件数据库 |
| Logback | 1.5.12 | 日志 |

## 📋 API 接口清单

所有接口路径与 App 端 API 定义**完全一致**：

### 用户认证（UserRoute）
| 方法 | 路径 | 说明 | 登录 |
|------|------|------|------|
| POST | `/app/user/login/password` | 密码登录 | ❌ |
| POST | `/app/user/login/qq` | QQ 登录（占位） | ❌ |
| POST | `/app/user/login/phone` | 短信登录 | ❌ |
| POST | `/app/user/login/refreshToken` | 刷新 Token | ❌ |
| GET | `/app/user/login/captcha` | 图形验证码 | ❌ |
| POST | `/app/user/login/smsCode` | 发送短信验证码 | ❌ |
| POST | `/app/user/login/register` | 注册 | ❌ |
| POST | `/app/user/password/reset` | 找回密码 | ❌ |
| POST | `/app/user/password/change` | 修改密码 | ✅ |
| GET | `/app/user/info/person` | 我的信息 | ✅ |
| GET | `/app/order/info/userCount` | 订单统计 | ✅ |
| GET | `/app/user/info/logoff` | 登出 | ✅ |

### 首页（HomeRoute）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/app/page/home` | 首页聚合 |
| POST | `/app/goods/type/list` | 分类列表 |
| POST | `/app/goods/info/page` | 商品分页 |

### 商品（GoodsRoute）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/app/page/goodsDetail` | 商品详情 |
| POST | `/app/goods/spec/list` | 规格列表 |
| POST | `/app/goods/comment/page` | 评论分页 |
| POST | `/app/goods/search/keyword/list` | 热门搜索词 |

### 订单（OrderRoute）
| 方法 | 路径 | 说明 | 登录 |
|------|------|------|------|
| POST | `/app/order/info/create` | 创建订单 | ✅ |
| POST | `/app/order/info/page` | 订单列表 | ✅ |
| GET | `/app/page/confirmOrder` | 确认订单页 | ✅ |
| POST | `/app/order/pay/alipayAppPay` | 支付宝支付 | ✅ |
| GET | `/app/order/info/logistics` | 物流信息 | ✅ |
| POST | `/app/order/info/cancel` | 取消订单 | ✅ |
| POST | `/app/order/info/refund` | 申请退款 | ✅ |
| GET | `/app/order/info/info` | 订单详情 | ✅ |
| GET | `/app/order/info/confirm` | 确认收货 | ✅ |
| POST | `/app/dict/info/data` | 字典数据 | ❌ |
| POST | `/app/goods/comment/submit` | 提交评论 | ✅ |

### 其他模块
- **AddressRoute**：`/app/user/address/*`（5 个接口）
- **CouponRoute**：`/app/market/coupon/*`（3 个接口）
- **CollectionRoute**：`/app/market/collect/*`（4 个接口）
- **NotificationRoute**：`/app/market/notice/*`（4 个接口）
- **FeedbackRoute**：`/app/app/feedback/*`（2 个接口 + 字典）
- **CustomerServiceRoute**：`/app/cs/*`（3 个接口）
- **UploadRoute**：`/app/base/comm/upload`（获取上传凭证）

## 🗄️ 数据库

H2 嵌入式数据库，首次启动自动建表，数据存在 `./data/shop` 文件中。

### 数据表清单（12 张）

| 表名 | 说明 |
|------|------|
| t_user | 用户 |
| t_address | 收货地址 |
| t_category | 商品分类 |
| t_goods | 商品 |
| t_order | 订单 |
| t_order_item | 订单商品 |
| t_coupon | 优惠券 |
| t_user_coupon | 用户持有的优惠券 |
| t_collection | 商品收藏 |
| t_notification | 消息通知 |
| t_feedback | 用户反馈 |
| t_cs_session / t_cs_msg | 客服会话/消息（待实现） |

### 换数据库

把 `DatabaseConfig.connect()` 里的 URL 和 driver 换成 MySQL 或 PostgreSQL 即可：

```kotlin
// MySQL 示例
Database.connect(
    url = "jdbc:mysql://localhost:3306/shop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
    user = "root", password = "password",
    driver = "com.mysql.cj.jdbc.Driver"
)
```

## 🔐 认证流程

```
App                                    Server
 │                                       │
 │── POST /app/user/login/password ───▶ │
 │                                       │ 校验密码
 │                                       │ 生成 AccessToken + RefreshToken
 │◀── { token, refreshToken, ... } ─── │
 │                                       │
 │── GET /app/user/info/person ───────▶ │
 │    Header: Authorization: <token>     │
 │                                       │ 校验 JWT 签名 + 过期
 │                                       │ 解析 userId
 │◀── { data: { id, nickname, ... } } ─ │
 │                                       │
 │    (Token 过期)                       │
 │── POST /app/user/login/refreshToken ▶ │
 │    Body: { refreshToken }             │
 │                                       │ 校验 RefreshToken
 │                                       │ 生成新 Token 对
 │◀── { token, refreshToken, ... } ─── │
```

## 📝 开发路线图

- [x] 骨架搭建 + 所有 47 个接口路由
- [x] H2 数据库 + 12 张表自动建表
- [x] JWT 认证 + RefreshToken 刷新
- [x] 注册 / 密码登录（明文，待 BCrypt）
- [ ] 所有路由接入真实数据库查询（替换 Mock 数据）
- [ ] 密码 BCrypt 加密
- [ ] 商品管理后台（Web 管理端）
- [ ] 支付宝 / 微信支付真实对接
- [ ] 腾讯云 COS 真实上传凭证
- [ ] 短信验证码服务（阿里云 / 腾讯云 SMS）
- [ ] WebSocket 客服实时消息
- [ ] Docker 化部署

## ❓ 常见问题

**Q: App 连不上后端？**
A: 检查局域网 IP、防火墙是否放行 8080 端口、App 是否用的 HTTP（需开启 `usesCleartextTraffic`）。

**Q: 数据库文件在哪？**
A: 项目根目录的 `./data/shop`（H2 文件数据库）。删了这个文件就重置所有数据。

**Q: 怎么换端口？**
A: 改 `AppConfig.PORT`，默认 8080。

**Q: JWT 密钥在哪改？**
A: `AppConfig.Jwt.SECRET`，生产环境一定要换成随机长字符串。
