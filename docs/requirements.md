# ShopServer 食物电商平台 — 需求文档

> 最后更新：2026-10-20
> 状态说明：✅ 已实现 · ⚠️ 占位/Demo · ❌ 缺失待开发

---

## 一、项目概述

ShopServer 是一个面向「食物」垂直领域的电商后端服务，配套 Android App 与 Web 管理后台。核心差异化在于：

- **菜谱 Tab** 作为底部四大 Tab 之一，替代传统电商的「分类」Tab，提供菜谱浏览、食材清单、一键加购所有食材的差异化购物体验
- 围绕食物场景做商品、菜谱、购物车、订单、优惠券等完整电商链路

### 技术栈

| 层 | 技术 |
|---|---|
| 后端框架 | Ktor (Kotlin) |
| ORM | Exposed |
| 数据库 | H2（原型）/ PostgreSQL（生产） |
| 认证 | JWT（App端）+ 独立 JWT（管理后台） |
| 前端管理后台 | 原生 HTML/CSS/JS（无构建依赖） |

---

## 二、用户角色

| 角色 | 说明 | 核心行为 |
|---|---|---|
| App 游客 | 未登录用户 | 浏览首页、搜索商品、看菜谱、查看食物详情 |
| App 登录用户 | 注册/登录后用户 | 下单、收藏、领券、写评论、管理地址、反馈 |
| 管理员 | 运营人员 | 管理商品/分类/订单/用户/优惠券/菜谱/轮播图 |

---

## 三、App 端功能模块

底部导航固定 4 个 Tab：**首页 · 菜谱 · 购物车 · 我的**

### 3.1 首页 Tab

| 功能 | 说明 | 后端接口 | 状态 |
|---|---|---|---|
| 轮播图展示 | 首页顶部可点击图片轮播 | `GET /app/page/home` → banner | ✅ |
| 分类入口 | 宫格展示顶级分类图标 | `GET /app/page/home` → category | ✅ |
| 限时精选（flashSale） | featured 标记商品 | `GET /app/page/home` → flashSale | ✅ |
| 推荐商品（recommend） | recommend 标记商品 | `GET /app/page/home` → recommend | ✅ |
| 优惠券入口 | 首页展示可领优惠券 | `GET /app/page/home` → coupon | ✅ |
| 食品瀑布流 / 列表 | 首页下拉加载更多商品 | `POST /app/foods/home/page` | ✅ |
| 全部分类列表 | 扁平带 parentId，前端组装层级 | `POST /app/foods/type/list` | ✅ |

### 3.2 菜谱 Tab（核心差异化）

| 功能 | 说明 | 后端接口 | 状态 |
|---|---|---|---|
| 菜谱分类筛选 | 按菜品分类（家常菜/汤品/主食等）过滤 | `GET /app/recipe/categories` | ✅ |
| 菜谱列表 | 分页展示菜谱卡片（封面图、名称、难度、耗时） | `GET /app/recipe/list?category=&page=` | ✅ |
| 菜谱详情 | 展示完整做法 + **食材清单** | `GET /app/recipe/detail?id=` | ✅ |
| 食材-商品关联 | 食材条目可关联到商城商品，显示商品名 | 菜谱详情接口已返回 goodsId/goodsTitle | ✅ |
| 一键加购所有食材 | 将食材关联的商品全部加入购物车 | App 端调用 `POST /app/cart/add` 批量执行 | ✅（前端编排） |
| 菜谱搜索 | 按菜名搜索菜谱 | ❌ 缺失 |
| 菜谱收藏 | 用户收藏喜欢的菜谱 | ❌ 缺失 |

### 3.3 购物车 Tab

| 功能 | 说明 | 后端接口 | 状态 |
|---|---|---|---|
| 购物车列表 | 展示已加购商品、价格、数量、勾选状态 | `POST /app/cart/list` | ✅ |
| 加入购物车 | 商品详情页加购，同商品同规格自动累加 | `POST /app/cart/add` | ✅ |
| 修改数量 | +/- 调整数量 | `POST /app/cart/update` | ✅ |
| 删除商品 | 支持批量删除 | `POST /app/cart/delete` | ✅ |
| 勾选 / 取消勾选 | 单条或全选 | `POST /app/cart/select` | ✅ |
| 清空已勾选 | 结算后清除已选商品 | `POST /app/cart/clearChecked` | ✅ |
| 购物车角标数量 | 首页/底部导航显示未勾选外的商品总数 | ❌ 缺失（可由 App 端本地计算） |

### 3.4 我的 Tab

| 功能 | 子功能 | 后端接口 | 状态 |
|---|---|---|---|
| 个人信息 | 查看头像、昵称、手机号 | `GET /app/user/info/person` | ✅ |
| 个人信息 | **编辑昵称/头像** | ❌ 缺失接口 |
| 订单中心 | 订单状态角标（待支付/已发货/待收货等数量） | `GET /app/order/info/userCount` | ✅ |
| 订单中心 | 按状态筛选订单列表 | `POST /app/order/info/page` | ✅ |
| 订单中心 | 订单详情 | `GET /app/order/info/info?id=` | ✅ |
| 订单中心 | 取消订单 | `POST /app/order/info/cancel` | ✅ |
| 订单中心 | 确认收货 | `GET /app/order/info/confirm?orderId=` | ✅ |
| 订单中心 | 申请退款 | `POST /app/order/info/refund` | ✅ |
| 订单中心 | 查看物流 | `GET /app/order/info/logistics` | ⚠️ 硬编码 Demo 数据 |
| 收货地址 | 地址列表 / 新增 / 编辑 / 删除 / 设为默认 | 4 个接口，见 AddressRoute | ✅ |
| 优惠券 | 我的优惠券列表 | `POST /app/market/coupon/user/page` | ✅ |
| 优惠券 | 优惠券可领取列表 | `POST /app/market/coupon/info/page` | ✅ |
| 优惠券 | 领取优惠券 | `POST /app/market/coupon/user/receive` | ✅ |
| 收藏 | 收藏商品列表 | `POST /app/market/collect/page` | ✅ |
| 收藏 | 收藏 / 取消收藏 / 是否已收藏 | 3 个接口 | ✅ |
| 消息通知 | 通知列表 / 未读数 / 标记已读 / 全部已读 | 4 个接口 | ✅ |
| 用户反馈 | 提交反馈（带图片） / 查看历史反馈 | 2 个接口 | ✅ |
| 客服 | 创建会话 / 消息列表 / 发送消息 | 3 个接口 | ⚠️ 全部硬编码占位 |
| 设置 | 修改密码（原密码 + 新密码） | `POST /app/user/password/change` | ✅ |
| 设置 | 找回密码（短信验证码） | `POST /app/user/password/reset` | ⚠️ 无真实短信服务 |
| 设置 | 退出登录 | `GET /app/user/info/logoff` | ✅ |
| 设置 | App 版本检查 / 更新提醒 | ❌ 缺失 |
| 浏览历史 | 记录和查看浏览过的商品 | ❌ 缺失 |

### 3.5 商品详情页（跨 Tab 公共页面）

| 功能 | 说明 | 后端接口 | 状态 |
|---|---|---|---|
| 食物详情 | 标题、副标题、主图、多图、价格、销量、详情图 | `GET /app/page/foodsDetail?foodsId=` | ✅ |
| 规格选择 | 不同规格对应不同价格和库存 | `POST /app/foods/spec/list` | ✅ |
| 评论列表 | 用户评论分页（含昵称、头像、内容、时间） | `POST /app/foods/comment/page` | ✅ |
| 提交评论 | 已登录用户可评论（关联订单可选） | `POST /app/foods/comment/submit` | ✅ |
| 收藏 / 已收藏状态 | 见 3.4 收藏 | — | ✅ |
| 加入购物车 / 立即购买 | 调用购物车 / 创建订单接口 | — | ✅ |

### 3.6 搜索（跨 Tab 公共功能）

| 功能 | 说明 | 后端接口 | 状态 |
|---|---|---|---|
| 热门关键词 | 搜索框下方展示推荐关键词 | `POST /app/foods/search/keyword/list` | ✅ |
| 食物搜索 | 关键词 + 分类 + 价格区间 + 排序 | `POST /app/foods/info/page` | ✅ |
| 搜索历史 | 本地缓存或后端存储用户搜索记录 | ❌ 缺失 |

### 3.7 登录 / 注册

| 功能 | 说明 | 后端接口 | 状态 |
|---|---|---|---|
| 密码登录 | 手机号 + 密码 | `POST /app/user/login/password` | ✅ |
| 短信验证码登录 | 手机号 + 验证码 | `POST /app/user/login/phone` | ⚠️ 验证码固定 123456 |
| 获取短信验证码 | 发送验证码到手机 | `POST /app/user/login/smsCode` | ⚠️ Demo 模式，无真实发送 |
| 图形验证码 | 滑块/点选验证码 | `GET /app/user/login/captcha` | ⚠️ 返回空占位 |
| QQ / 微信三方登录 | — | `POST /app/user/login/qq` | ❌ 返回"暂未开放" |
| 注册 | 手机号 + 密码 + 验证码 | `POST /app/user/login/register` | ✅ |
| 刷新 Token | AccessToken 过期后用 RefreshToken 换新 | `POST /app/user/login/refreshToken` | ✅ |

### 3.8 订单流程

| 步骤 | 功能 | 后端接口 | 状态 |
|---|---|---|---|
| 1 | 确认订单页（地址列表 + 可用优惠券） | `GET /app/page/confirmOrder` | ✅ |
| 2 | 创建订单 | `POST /app/order/info/create` | ✅ |
| 3 | 发起支付 | `POST /app/order/pay/alipayAppPay` | ⚠️ 仅标记已支付，无真实支付网关对接 |
| 4 | 支付回调 | 支付宝/微信异步通知 | ❌ 缺失 |
| 5 | 发货 | 管理员后台操作 | 管理后台接口 | ✅ |
| 6 | 查看物流 | 物流轨迹展示 | `GET /app/order/info/logistics` | ⚠️ 硬编码 |
| 7 | 确认收货 | 用户手动确认 | `GET /app/order/info/confirm` | ✅ |
| 8 | 订单超时自动取消 | 待支付订单超时自动关闭 | ❌ 缺失（定时任务） |
| 9 | 退款审批 | 管理员审核用户退款申请 | ❌ 缺失 |
| 10 | 优惠券核销 | 订单完成后标记 UserCoupon 为已使用 | ❌ 缺失（创建订单时未更新） |
| 11 | 库存扣减 | 下单时扣减 GoodsSpecs.stock | ❌ 缺失 |

### 3.9 文件上传

| 功能 | 说明 | 后端接口 | 状态 |
|---|---|---|---|
| 获取上传凭证 | App 端直传腾讯云 COS | `POST /app/base/comm/upload` | ⚠️ 返回 Demo 凭证 |

### 3.10 字典数据

| 功能 | 说明 | 后端接口 | 状态 |
|---|---|---|---|
| 订单取消原因 | 前端展示下拉选项 | `POST /app/dict/info/data {type: "order_cancel_reason"}` | ✅ |
| 退款原因 | 前端展示下拉选项 | `POST /app/dict/info/data {type: "order_refund_reason"}` | ✅ |

---

## 四、管理后台功能模块

### 4.1 仪表盘

| 功能 | 说明 | 状态 |
|---|---|---|
| 核心指标 | 食物总数、订单总数、用户总数、优惠券总数、已支付订单、总销售额 | ✅ |
| 近7天销售趋势 | 折线图数据 | ✅ |
| 订单状态分布 | 饼图数据 | ✅ |
| 热销商品 TOP5 | 排行榜 | ✅ |
| 最近5条订单 | 列表 | ✅ |

### 4.2 食物管理

| 功能 | 说明 | 状态 |
|---|---|---|
| 食物列表 | 分页 + 关键词搜索 | ✅ |
| 新增食物 | 完整字段（类型、标题、图片、价格、推荐标记、规格等） | ✅ |
| 编辑食物 | 详情回显 + 更新字段 | ✅ |
| 删除食物 | 软删除（下架） | ✅ |
| 规格管理 | 规格列表 / 新增 / 编辑 / 删除 | ✅ |
| 图片上传 | Base64 方式上传本地存储 | ✅ |

### 4.3 分类管理

| 功能 | 说明 | 状态 |
|---|---|---|
| 分类列表 | 扁平展示，含 parentId 支持两级 | ✅ |
| 新增 / 编辑 / 删除 | 删除校验子分类和商品关联 | ✅ |

### 4.4 订单管理

| 功能 | 说明 | 状态 |
|---|---|---|
| 订单列表 | 分页 + 按状态筛选 | ✅ |
| 订单详情 | 完整订单信息 + 商品列表 + 地址快照 | ✅ |
| 订单发货 | 标记为已发货 | ✅ |
| 退款审批 | 审核用户退款申请（同意/拒绝） | ❌ 缺失 |
| 手动关闭订单 | 管理员手动取消异常订单 | ❌ 缺失 |

### 4.5 用户管理

| 功能 | 说明 | 状态 |
|---|---|---|
| 用户列表 | 分页 + 关键词搜索 | ✅ |
| 启用 / 禁用用户 | 修改 status | ✅ |
| 编辑用户 | 修改昵称、密码 | ✅ |
| 用户详情 | 查看用户订单、收藏、地址 | ❌ 缺失 |

### 4.6 优惠券管理

| 功能 | 说明 | 状态 |
|---|---|---|
| 优惠券列表 | 展示所有优惠券及剩余数量 | ✅ |
| 新增 / 编辑 / 删除 | 完整 CRUD | ✅ |

### 4.7 菜谱管理

| 功能 | 说明 | 状态 |
|---|---|---|
| 菜谱列表 | 分页 + 关键词搜索 | ✅ |
| 菜谱详情 / 新增 / 编辑 / 删除 | 完整 CRUD，含食材清单 | ✅ |
| 食材管理 | 菜谱关联食材的增删改 | ✅ |

### 4.8 轮播图管理

| 功能 | 说明 | 状态 |
|---|---|---|
| 轮播图列表 / 新增 / 编辑 / 删除 | 完整 CRUD，支持启用/禁用 | ✅ |

### 4.9 搜索关键词管理

| 功能 | 说明 | 状态 |
|---|---|---|
| 关键词列表 / 新增 / 编辑 / 删除 | 完整 CRUD，支持热门标记和排序 | ✅ |

### 4.10 反馈管理

| 功能 | 说明 | 状态 |
|---|---|---|
| 用户反馈列表 | 查看用户提交的反馈 | ❌ 缺失（只有 App 端提交，管理后台无列表） |
| 回复反馈 | 管理员回复用户 | ❌ 缺失 |

---

## 五、数据库表模型（共 16 张表）

| 表名 | 说明 | 状态 |
|---|---|---|
| t_user | 用户（手机号、密码、昵称、头像、状态） | ✅ |
| t_address | 收货地址（支持多地址、默认标记） | ✅ |
| t_category | 商品分类（支持两级，parentId） | ✅ |
| t_goods | 商品/食物 | ✅ |
| t_goods_spec | 商品规格（多规格、各自价格和库存） | ✅ |
| t_order | 订单 | ✅ |
| t_order_item | 订单商品项 | ✅ |
| t_coupon | 优惠券模板 | ✅ |
| t_user_coupon | 用户优惠券（status: 未使用/已使用/已过期） | ✅ |
| t_cart | 购物车 | ✅ |
| t_collection | 商品收藏 | ✅ |
| t_comment | 商品评论 | ✅ |
| t_notification | 消息通知 | ✅ |
| t_feedback | 用户反馈 | ✅ |
| t_banner | 首页轮播图 | ✅ |
| t_search_keyword | 热门搜索关键词 | ✅ |
| t_recipe | 菜谱 | ✅ |
| t_recipe_ingredient | 菜谱食材（可关联 t_goods） | ✅ |

---

## 六、后端 API 接口清单（按路由模块）

### 6.1 首页接口（HomeRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| GET | /app/page/home | 首页聚合数据（轮播+分类+精选+推荐+优惠券） | 免登录 | ✅ |
| POST | /app/foods/type/list | 全部分类列表（扁平结构） | 免登录 | ✅ |

### 6.2 食物接口（FoodsRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| GET | /app/page/foodsDetail | 食物详情 | 免登录 | ✅ |
| POST | /app/foods/spec/list | 规格列表 | 免登录 | ✅ |
| POST | /app/foods/comment/page | 评论分页 | 免登录 | ✅ |
| POST | /app/foods/home/page | 首页瀑布流商品分页 | 免登录 | ✅ |
| POST | /app/foods/info/page | 搜索/筛选商品分页 | 免登录 | ✅ |
| POST | /app/foods/search/keyword/list | 热门搜索关键词 | 免登录 | ✅ |

### 6.3 菜谱接口（RecipesRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| GET | /app/recipe/categories | 菜谱分类列表 | 免登录 | ✅ |
| GET | /app/recipe/list | 菜谱列表（可按 category 筛选） | 免登录 | ✅ |
| GET | /app/recipe/detail | 菜谱详情（含食材清单） | 免登录 | ✅ |

### 6.4 用户认证接口（UserRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| POST | /app/user/login/password | 密码登录 | 免登录 | ✅ |
| POST | /app/user/login/phone | 短信登录 | 免登录 | ✅ |
| POST | /app/user/login/qq | QQ 登录 | 免登录 | ❌ 占位 |
| POST | /app/user/login/refreshToken | 刷新 Token | 免登录 | ✅ |
| GET | /app/user/login/captcha | 图形验证码 | 免登录 | ⚠️ 占位 |
| POST | /app/user/login/smsCode | 发送短信验证码 | 免登录 | ⚠️ Demo |
| POST | /app/user/login/register | 注册 | 免登录 | ✅ |
| POST | /app/user/password/reset | 找回密码 | 免登录 | ⚠️ 无短信 |
| POST | /app/user/password/change | 修改密码 | JWT | ✅ |
| GET | /app/user/info/person | 个人信息 | JWT | ✅ |
| GET | /app/user/info/logoff | 登出 | JWT | ✅ |
| POST | /app/user/info/update | **编辑昵称/头像** | JWT | ❌ 缺失 |

### 6.5 订单接口（OrderRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| POST | /app/dict/info/data | 字典数据（取消/退款原因） | 免登录 | ✅ |
| POST | /app/order/info/create | 创建订单 | JWT | ✅ |
| POST | /app/order/info/page | 订单列表（分页+状态筛选） | JWT | ✅ |
| GET | /app/page/confirmOrder | 确认订单页数据 | JWT | ✅ |
| POST | /app/order/pay/alipayAppPay | 发起支付 | JWT | ⚠️ 占位 |
| GET | /app/order/info/logistics | 物流信息 | JWT | ⚠️ 硬编码 |
| POST | /app/order/info/cancel | 取消订单 | JWT | ✅ |
| POST | /app/order/info/refund | 申请退款 | JWT | ✅ |
| GET | /app/order/info/info | 订单详情 | JWT | ✅ |
| GET | /app/order/info/confirm | 确认收货 | JWT | ✅ |
| POST | /app/foods/comment/submit | 提交评论 | JWT | ✅ |
| GET | /app/order/info/userCount | 我的订单统计 | JWT | ✅ |
| POST | /app/payment/alipay/notify | **支付宝支付回调** | 公钥验签 | ❌ 缺失 |
| POST | /app/payment/wechat/notify | **微信支付回调** | 公钥验签 | ❌ 缺失 |

### 6.6 购物车接口（CartRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| POST | /app/cart/list | 购物车列表 | JWT | ✅ |
| POST | /app/cart/add | 加入购物车 | JWT | ✅ |
| POST | /app/cart/update | 修改数量 | JWT | ✅ |
| POST | /app/cart/delete | 删除（批量） | JWT | ✅ |
| POST | /app/cart/select | 勾选/全选 | JWT | ✅ |
| POST | /app/cart/clearChecked | 清空已勾选 | JWT | ✅ |

### 6.7 收货地址接口（AddressRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| GET | /app/user/address/default | 默认地址 | JWT | ✅ |
| POST | /app/user/address/list | 地址列表 | JWT | ✅ |
| POST | /app/user/address/add | 新增地址 | JWT | ✅ |
| POST | /app/user/address/update | 修改地址 | JWT | ✅ |
| POST | /app/user/address/delete | 删除地址 | JWT | ✅ |

### 6.8 优惠券接口（CouponRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| POST | /app/market/coupon/info/page | 可领优惠券列表 | 免登录 | ✅ |
| POST | /app/market/coupon/user/page | 我的优惠券 | JWT | ✅ |
| POST | /app/market/coupon/user/receive | 领取优惠券 | JWT | ✅ |

### 6.9 收藏接口（CollectionRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| POST | /app/market/collect/page | 收藏列表 | JWT | ✅ |
| POST | /app/market/collect/add | 添加收藏 | JWT | ✅ |
| POST | /app/market/collect/delete | 取消收藏 | JWT | ✅ |
| POST | /app/market/collect/isCollect | 是否已收藏 | JWT | ✅ |

### 6.10 消息通知接口（NotificationRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| POST | /app/market/notice/page | 通知列表 | JWT | ✅ |
| POST | /app/market/notice/unread | 未读数 | JWT | ✅ |
| POST | /app/market/notice/read | 标记已读 | JWT | ✅ |
| POST | /app/market/notice/readAll | 全部已读 | JWT | ✅ |

### 6.11 用户反馈接口（FeedbackRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| POST | /app/feedback/page | 反馈历史 | JWT | ✅ |
| POST | /app/feedback/submit | 提交反馈 | JWT | ✅ |

### 6.12 客服接口（CustomerServiceRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| POST | /app/cs/session/create | 创建会话 | JWT | ⚠️ 占位 |
| POST | /app/cs/msg/page | 消息历史 | JWT | ⚠️ 硬编码 |
| POST | /app/cs/msg/send | 发送消息 | JWT | ⚠️ 占位 |

### 6.13 文件上传接口（UploadRoute）
| 方法 | 路径 | 说明 | 鉴权 | 状态 |
|---|---|---|---|---|
| POST | /app/base/comm/upload | 获取 COS 直传凭证 | JWT | ⚠️ Demo |

### 6.14 管理后台接口（AdminRoute）
全部以 `/admin/` 开头，除 login 外需 Admin JWT 鉴权。

| 方法 | 路径 | 说明 | 状态 |
|---|---|---|---|
| POST | /admin/login | 管理员登录 | ✅ |
| GET | /admin/dashboard/stats | 仪表盘统计 | ✅ |
| GET | /admin/foods/list | 食物列表 | ✅ |
| GET | /admin/foods/detail | 食物详情 | ✅ |
| POST | /admin/foods/add | 新增食物 | ✅ |
| PUT | /admin/foods/update | 更新食物 | ✅ |
| DELETE | /admin/foods/delete | 删除食物 | ✅ |
| GET | /admin/foods/spec/list | 规格列表 | ✅ |
| POST | /admin/foods/spec/add | 新增规格 | ✅ |
| PUT | /admin/foods/spec/update | 更新规格 | ✅ |
| DELETE | /admin/foods/spec/delete | 删除规格 | ✅ |
| GET | /admin/order/list | 订单列表 | ✅ |
| GET | /admin/order/detail | 订单详情 | ✅ |
| POST | /admin/order/ship | 订单发货 | ✅ |
| GET | /admin/user/list | 用户列表 | ✅ |
| POST | /admin/user/updateStatus | 更新用户状态 | ✅ |
| PUT | /admin/user/edit | 编辑用户 | ✅ |
| GET | /admin/coupon/list | 优惠券列表 | ✅ |
| POST | /admin/coupon/add | 新增优惠券 | ✅ |
| PUT | /admin/coupon/update | 更新优惠券 | ✅ |
| DELETE | /admin/coupon/delete | 删除优惠券 | ✅ |
| GET | /admin/category/list | 分类列表 | ✅ |
| POST | /admin/category/add | 新增分类 | ✅ |
| PUT | /admin/category/update | 更新分类 | ✅ |
| DELETE | /admin/category/delete | 删除分类 | ✅ |
| GET | /admin/banner/list | 轮播图列表 | ✅ |
| POST | /admin/banner/add | 新增轮播图 | ✅ |
| PUT | /admin/banner/update | 更新轮播图 | ✅ |
| DELETE | /admin/banner/delete | 删除轮播图 | ✅ |
| GET | /admin/keyword/list | 搜索关键词列表 | ✅ |
| POST | /admin/keyword/add | 新增关键词 | ✅ |
| PUT | /admin/keyword/update | 更新关键词 | ✅ |
| DELETE | /admin/keyword/delete | 删除关键词 | ✅ |
| POST | /admin/upload/image | 图片上传（base64） | ✅ |
| GET | /admin/recipe/list | 菜谱列表 | ✅ |
| GET | /admin/recipe/detail | 菜谱详情 | ✅ |
| POST | /admin/recipe/add | 新增菜谱 | ✅ |
| PUT | /admin/recipe/update | 更新菜谱 | ✅ |
| DELETE | /admin/recipe/delete | 删除菜谱 | ✅ |
| POST | /admin/recipe/ingredient/add | 新增食材 | ✅ |
| PUT | /admin/recipe/ingredient/update | 更新食材 | ✅ |
| DELETE | /admin/recipe/ingredient/delete | 删除食材 | ✅ |

---

## 七、待完善功能清单（后续迭代方向）

以下是当前缺失或仅为占位的功能，按优先级排序：

### P0 — 必须补齐的业务闭环

| # | 功能 | 涉及模块 | 说明 |
|---|---|---|---|
| 1 | **用户资料编辑** | App 端 / UserRoute | 新增 `POST /app/user/info/update` 接口，支持修改昵称、头像 |
| 2 | **库存扣减** | OrderRoute | 创建订单时必须扣减对应 GoodsSpecs.stock，防止超卖 |
| 3 | **优惠券核销** | OrderRoute | 订单创建成功后将 UserCoupon.status 置为 1（已使用） |
| 4 | **支付回调** | 新增模块 | 对接支付宝/微信支付的异步通知接口，验签后更新订单状态 |
| 5 | **退款审批** | 管理后台 | 管理员审核用户退款申请，同意则更新订单为已退款、退券、退款 |
| 6 | **订单超时自动取消** | 定时任务 | 待支付订单超过 15 分钟自动关闭，释放库存 |

### P1 — 提升体验的重要功能

| # | 功能 | 涉及模块 | 说明 |
|---|---|---|---|
| 7 | **浏览历史** | 新增表 t_browse_history | 记录用户查看过的商品，支持查看和清除 |
| 8 | **搜索历史** | 新增表 t_search_history | 记录用户搜索关键词，支持本地+后端同步 |
| 9 | **App 版本检查** | 新增接口 | 返回最新版本号、下载链接、是否强制更新 |
| 10 | **管理后台反馈管理** | AdminRoute | 反馈列表 + 回复接口，让用户能收到管理员回复 |
| 11 | **菜谱搜索** | RecipesRoute | 按菜名关键词搜索菜谱 |
| 12 | **菜谱收藏** | 新增表 t_recipe_collection | 用户收藏喜欢的菜谱 |

### P2 — Demo 转生产的对接工作

| # | 功能 | 涉及模块 | 说明 |
|---|---|---|---|
| 13 | **密码 BCrypt 加密** | UserRoute / User 表 | 当前明文存储密码，需引入 BCrypt |
| 14 | **真实短信服务** | UserRoute | 对接阿里云/腾讯云短信，替换 Demo 验证码 |
| 15 | **真实支付网关** | 新增模块 | 支付宝/微信支付 SDK 对接 |
| 16 | **真实物流查询** | OrderRoute | 对接快递 100/快递鸟 API，替换硬编码物流数据 |
| 17 | **腾讯云 COS 直传** | UploadRoute | 引入 COS STS SDK 生成真实临时凭证 |
| 18 | **客服 WebSocket** | 新增模块 | 用 WebSocket 替换 HTTP 轮询，实现实时聊天 |
| 19 | **QQ / 微信三方登录** | UserRoute | 对接 QQ / 微信开放平台 SDK |

---

## 八、技术债务与代码 TODO

以下是代码中已标记的 TODO 或已知问题：

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| 1 | UserRoute.kt:141 | 密码明文存储 `// TODO: BCrypt 加密` | 引入 `org.springframework.security:spring-security-crypto` 或单独的 BCrypt 库 |
| 2 | UserRoute.kt:159 | reset 密码同样明文 `// TODO: BCrypt` | 同上 |
| 3 | UploadRoute.kt | COS 凭证全部硬编码为 Demo | 引入腾讯云 STS SDK，配置真实 SecretId/SecretKey |
| 4 | CustomerServiceRoute.kt | 客服消息全部硬编码 | 后续用 WebSocket + 真实客服系统 |
| 5 | OrderRoute.kt:94-214 | 创建订单未扣库存、未核销优惠券 | 见待完善清单 P0-2、P0-3 |
| 6 | AdminAuth.kt | 管理后台鉴权白名单硬编码 | 后续可配置化 |
| 7 | 所有金额字段 | 以「分」为单位的 Int 类型 | 注意前端展示时要除以 100，保持一致 |
| 8 | H2 → PostgreSQL | 当前原型用 H2 内存数据库 | 生产环境切换时注意 SQL 兼容性 |

---

## 九、迭代计划建议

### Phase 1 — 业务闭环（当前目标）
补齐 P0 的 6 项功能，让订单流程跑通：下单 → 支付 → 发货 → 签收 → 退款，库存和优惠券正确核销。

### Phase 2 — 体验优化
实现 P1 的 6 项功能（浏览历史、搜索历史、版本检查、反馈管理、菜谱搜索/收藏）。

### Phase 3 — 生产对接
完成 P2 的 Demo→生产转换（短信、支付、物流、COS、客服、三方登录），切换 PostgreSQL 数据库。

---

## 附录：订单状态机

```
  ┌─────────────┐
  │  待支付(0)  │──────超时────▶ 已取消(4)
  └──────┬──────┘
         │ 支付成功
         ▼
  ┌─────────────┐
  │  已支付(1)  │────────────▶ 已取消(4)  [用户主动取消]
  └──────┬──────┘
         │ 管理员发货
         ▼
  ┌─────────────┐
  │  已发货(2)  │
  └──────┬──────┘
         │ 用户确认收货
         ▼
  ┌─────────────┐
  │  已签收(3)  │
  └──────┬──────┘
         │ 申请退款（任意已支付状态）
         ▼
  ┌─────────────┐
  │  退款中(5)  │──管理员同意──▶ 已退款(6)
  └─────────────┘
```
