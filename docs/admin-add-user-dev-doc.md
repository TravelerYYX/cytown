# 官方账号体系 & 用户密码登录 开发文档

> 适用范围：管理端可创建「官方账号」（`cyt` + 4 位随机数字）登录后台管理面板但只读；APP 端用户可自主设置密码并用「账号 + 密码」登录。

| 项目 | 内容 |
|---|---|
| 文档版本 | **v2.0**（v1.0 为「管理员添加 APP 用户」，需求变更，本版整体重写） |
| 编写日期 | 2026-09-10 |
| 状态 | 待评审（含待确认事项，见[第十三节](#十三风险与待确认事项)） |
| 涉及端 | 后端 `ciyuantoen-back/ciyuantown-back`、管理端 `ciyuantoen-back/ciyuantown-admin`、APP 端 `次元町` |
| 技术栈约束 | **零新增依赖**。沿用 Spring Boot 3.5.16 + Java 21 + MyBatis-Plus 3.5.12 + MySQL 8 + Hutool（BCrypt / JWT）+ Lombok，Redis 复用 `RedisHotspotService` |

### 版本变更说明

| 版本 | 范围 | 变更原因 |
|---|---|---|
| v1.0 | 管理员在后台创建 APP 用户（`app_user`） | 初稿 |
| **v2.0** | ① 创建**官方账号**（管理面板只读账号，带密码）<br>② APP 用户**可选设置密码**并支持账号密码登录 | 需求变更：开户对象从「C 端用户」改为「能进后台的官方只读账号」；同时为 C 端补一套可选的密码登录 |
| **v2.1** | ① 用户管理页增加「APP 用户 / 官方账号」页签（官方账号页签仅超管可见），恢复后台新建 APP 用户（`POST /app-user/create`：QQ号 + 昵称 + 可选初始密码，留空=仅验证码登录）<br>② **密码强度放宽**：仅要求 8-20 位且不含空格，**允许纯数字**（覆盖 6.6 强度表与 7.3 提示文案中的「非纯数字」限制）<br>③ **官方账号不再自动生成账号名**：改为固定前缀 `cyt` + 超管输入的 1-4 位数字（左补零到 4 位，重名直接提示），替代 6.3 的号池随机算法与「号段耗尽」分支 | 需求变更：用户管理需统一展示两类账号并支持建号；产品放宽密码规则、要求账号数字可指定。官方账号的创建/重置/启停能力内嵌至用户管理页签，「官方账号管理」独立页保留 |
| **v2.2** | **删除侧栏「官方账号管理」独立入口**：移除 `official-account-manage` 路由（`/official-account-manage`）与对应 i18n，原页面组件下沉为 `views/app-user-manage/modules/official-account.vue`，官方账号的列表/创建/重置密码/启停**全部并入用户管理页的「官方账号」页签** | 需求变更：两类账号管理需集中在一处，侧栏不再保留重复入口；独立页与页签功能完全重合，故只保留页签 |
| **v2.3** | **官方账号可用于 APP 登录**：① APP 登录页「密码登录」的账号输入框不再限制纯数字，支持 `cyt####`；② `POST /app/auth/loginByPassword` 的账号入参放宽为 `[0-9A-Za-z]{4,20}`，非纯数字账号按 `admin_user`（`user_type=1`）校验密码；③ 官方账号**首次登录 APP 时惰性建立绑定的社区身份** `app_user`（`qq_number = 官方账号名`，昵称/头像取官方账号），此后按普通 APP 用户使用；④ 官方账号密码始终以 `admin_user` 为准，APP 内「设置/修改密码」对官方账号直接拦截 | 缺陷修复：官方账号名为 `cyt####`（含字母），而 APP 密码登录输入框与后端参数都只接受 5-11 位纯数字，导致官方账号无法登录 APP |

> v1.0 中「管理员直接创建 `app_user`」的部分**已废弃，不再实施**（其中 v2.1 已按新需求恢复建号能力，实现沿用其「insert 后需回查」等结论）。

---

## 目录

1. [功能概述](#一功能概述)
2. [现状调研与约束](#二现状调研与约束)
3. [需求范围](#三需求范围)
4. [方案选型](#四方案选型)
5. [数据库设计](#五数据库设计)
6. [后端详细设计](#六后端详细设计)
7. [接口汇总](#七接口汇总)
8. [缓存与一致性](#八缓存与一致性)
9. [安全设计](#九安全设计)
10. [前端配套契约](#十前端配套契约)
11. [测试用例](#十一测试用例)
12. [开发任务拆分](#十二开发任务拆分)
13. [风险与待确认事项](#十三风险与待确认事项)

---

## 一、功能概述

本次交付两块相对独立的能力：

**A. 官方账号（Official Account）**

- 超管在管理面板创建官方账号，账号名由系统自动生成，格式 `cyt` + 4 位随机数字（如 `cyt7391`，范围 `cyt0000`–`cyt9999`）。
- 官方账号带密码，可登录**后端管理面板**。
- 官方账号**只读**：可以浏览所有页面与数据，但**不修改任何东西**（后端强拦截，不只是前端隐藏按钮）。
- 适用场景：运营、客服、审核人员需要看数据但不允许操作。

**B. APP 用户密码登录**

- `app_user` 新增可选密码字段，**不强制**用户设置。
- 用户在 APP「设置 → 通行证账号与安全」中设置密码；已设置的可修改。
- 设置后即可用「QQ 号 + 密码」登录。
- **未设置密码的用户仍走原有的「QQ 号 + 邮箱验证码」流程**，两套登录方式并存，互不影响。

**名词约定**

| 名词 | 含义 |
|---|---|
| 超管 / 超级管理员 | `admin_user.user_type = 0`，拥有全部权限，`role = R_SUPER` |
| 官方账号（只读） | `admin_user.user_type = 1`，仅可浏览，`role = R_OFFICIAL` |
| 普通用户 / APP 用户 | `app_user` 表中的 C 端账号，本文档中指**密码登录**的主体 |
| 管理面板 | `ciyuantown-admin`，超管与官方账号共用 |

---

## 二、现状调研与约束

### 2.1 现状代码位置

| 关注点 | 文件 | 现状 |
|---|---|---|
| 管理端登录 | `business/auth/controller/AuthController.java` | `POST /auth/login`、`/auth/refreshToken`、`/auth/getUserInfo`、`/auth/updatePassword` 等 |
| 管理端认证实现 | `business/auth/service/impl/AuthServiceImpl.java` | 按 `username` 查 `admin_user` → `BCrypt.checkpw` → 签发 JWT；`getUserInfo()` **硬编码 `roles = [R_SUPER]`** |
| 管理员实体 | `business/sys/entity/AdminUser.java` | 仅 `id / username / password / nickname / avatar / createTime / updateTime`，**无类型、无状态字段** |
| 管理端鉴权器 | `common/interceptor/AuthInterceptor.java` | 校验 admin access token（`aud = admin`），成功后 `request.setAttribute("userId", id)`；**不查库、不校验用户类型** |
| 拦截器注册 | `common/config/WebConfig.java` | `authInterceptor` 拦截 `/**`，排除 `/auth/login`、`/auth/refreshToken`、`/uploads/**`、`/app/**`、`/error` |
| JWT 工具 | `common/util/JwtUtil.java` | payload 含 `aud / userId / type / ver / jti`；**无用户类型字段** |
| APP 端登录 | `business/appauth/service/impl/AppAuthServiceImpl.java` | 仅「QQ 号 + 邮箱验证码」一种方式，验证码通过即隐式注册 |
| APP 用户实体 | `business/appuser/entity/AppUser.java` | **无密码字段** |
| APP 端鉴权器 | `common/interceptor/AppAuthInterceptor.java` | 查 `app_user` 校验 `status`、`token_version`，并比对 Redis `ct:session:ver:{id}`（踢下线机制现成） |
| 统一响应 | `common/api/ResultCode.java` | `0000` 成功 / `1000` 业务失败 / `9999` token 过期 / `8888` 未登录 |
| 前端角色过滤 | `ciyuantown-admin/src/store/modules/route/shared.ts` | `filterAuthRoutesByRoles`：`route.meta.roles` 为空则放行，否则需命中 |
| 前端超管常量 | `ciyuantown-admin/.env` | `VITE_AUTH_ROUTE_MODE=static`、`VITE_STATIC_SUPER_ROLE=R_SUPER` |
| APP 设置页入口 | `次元町/pages/settings/index.vue` 第 11–13 行 | 「通行证账号与安全」目前是 `openPlaceholder()` 占位，**改这里接入** |

### 2.2 五条硬约束

1. **身份源必须分开。** 官方账号是**后台账号**（`admin_user`），不是 C 端用户（`app_user`）。两者 JWT 的 `aud` 不同（`admin` vs `app`），混用会导致鉴权串味。
2. **`admin_user` 缺类型与状态字段。** 必须加列才能区分超管/官方账号、才能禁用官方账号。
3. **`AuthInterceptor` 不查库。** 现有管理端 JWT 签发后无法主动失效，禁用账号或改密码后旧 token 最长仍可用 2 小时（access token 有效期）。见 [6.8](#68-token-失效机制官方账号踢下线)。
4. **只读必须后端拦截。** 前端隐藏按钮只是体验优化，抓包即可绕过；必须在拦截器层统一拒绝写请求。
5. **`app_user.email` 无唯一索引。** 密码登录的账号只能用 `qq_number`（唯一），不能用邮箱，否则可能命中多条。

---

## 三、需求范围

### 3.1 A. 官方账号（P0）

| # | 需求 |
|---|---|
| A1 | 超管可在管理面板创建官方账号，账号名自动生成：`cyt` + 4 位随机数字 |
| A2 | 创建时设置初始密码（超管输入或系统随机生成并展示一次） |
| A3 | 官方账号可用「账号 + 密码」登录管理面板 |
| A4 | 官方账号可浏览所有页面与数据 |
| A5 | 官方账号**不能执行任何写操作**（增/删/改/启停/上传），后端强制拦截 |
| A6 | 超管可查看官方账号列表、重置密码、启用/禁用 |
| A7 | 官方账号无法看到/进入用户管理页的「官方账号」页签（仅超管可见；侧栏无独立入口） |
| A8 | 官方账号可用「官方账号名（`cyt####`）+ 后台设置的密码」登录 APP（次元町）；首次登录自动建立绑定的社区身份（v2.3） |

### 3.2 B. APP 用户密码登录（P0）

| # | 需求 |
|---|---|
| B1 | APP 用户在「设置 → 通行证账号与安全」中设置密码 |
| B2 | 已设置密码的用户可修改密码（需校验旧密码） |
| B3 | 设置后可用「QQ 号 + 密码」登录 |
| B4 | **不强制**设置密码；未设置的用户仍走验证码登录，流程不变 |
| B5 | 改密码后其他设备/旧 token 失效（踢下线） |
| B6 | 密码登录失败需限流，防暴力破解 |

### 3.3 明确不做

- 官方账号与 C 端社区账号的绑定（如需，见待确认 D1）
- 官方账号的角色细分（本期只有「只读」一种）
- 手机验证码登录、第三方登录
- 密码找回（本期重置密码走验证码登录或联系超管；见待确认 D5）

---

## 四、方案选型

### 4.1 官方账号落在哪张表

| 维度 | 方案 A：落在 `admin_user`（推荐） | 方案 B：落在 `app_user` |
|---|---|---|
| 密码字段 | 现成（BCrypt 已用于超管） | 需新增 |
| 登录链路 | `AuthServiceImpl.login()` 小改即可 | 管理端登录需跨表查两套身份源 |
| JWT / 鉴权 | 天然 `aud = admin`，`AuthInterceptor` 直接放行 | 需改造 `aud` 语义，风险高 |
| `getUserInfo` | 同一张表，改动小 | 需兼容两种来源 |
| 与 C 端账号混淆风险 | 无 | 高（同一 QQ 用户既是 C 端用户又是后台账号） |

**结论：方案 A。** 官方账号本质是后台账号，与 C 端用户完全隔离。

### 4.2 只读权限怎么实现

| 层 | 措施 | 是否必需 |
|---|---|---|
| 前端路由 | `route.meta.roles` 控制菜单可见性（现成机制） | 体验 |
| 前端按钮 | 按 role 隐藏操作按钮 | 体验 |
| **后端拦截器** | 新增只读拦截器，拒绝所有非 GET 写请求 | **安全必需** |
| 数据库 | 理论上可用只读账号连接，成本过高，不做 | — |

### 4.3 密码登录与验证码登录的关系

**并存，不替换。** `app_user.password` 为 `NULL` 表示未设置密码，此时密码登录接口明确拒绝并提示走验证码；验证码登录逻辑一行不改。

---

## 五、数据库设计

### 5.1 `admin_user` 新增列（迁移脚本 `doc/sql/p8-official-account.sql`）

| 列 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `user_type` | TINYINT | `0` | `0` 超级管理员，`1` 官方只读账号。加 `CONSTRAINT chk_admin_type CHECK(user_type IN (0,1))` |
| `status` | TINYINT | `0` | `0` 正常，`1` 禁用。加 `CONSTRAINT chk_admin_status CHECK(status IN (0,1))` |
| `token_version` | INT | `0` | 用于踢下线（见 6.8），与 `app_user` 语义一致 |
| `created_by` | BIGINT NULL | NULL | 创建人（超管 id），审计用 |

> 存量 `admin_user` 全部为超管，默认值 `0` 天然兼容，无需数据订正。

### 5.2 `app_user` 新增列（迁移脚本 `doc/sql/p9-app-user-password.sql`）

| 列 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `password` | VARCHAR(100) NULL | NULL | 登录密码（BCrypt 存储）。**NULL = 未设置密码**，此时仅支持验证码登录 |

> 100 长度与 `admin_user.password` 对齐，足够存放 BCrypt（60 字符）并留余量。
> 存量用户全部为 NULL，即默认未设置密码，验证码流程完全不受影响。

### 5.3 索引

| 表 | 索引 | 说明 |
|---|---|---|
| `admin_user` | 已有 `uk_username` | 官方账号名唯一性由它兜底，**无需新增索引** |
| `app_user` | 已有 `uk_qq` | 密码登录账号唯一性由它保证 |

### 5.4 字段写入规则（创建官方账号）

| 字段 | 来源 | 写入值 |
|---|---|---|
| `username` | 系统生成 | `cyt` + 4 位随机数字（见 6.3） |
| `password` | 入参 | `BCrypt.hashpw(明文, BCrypt.gensalt())` |
| `nickname` | 入参 | 展示名，如「官方运营-张三」，可空（前端回退显示 username） |
| `avatar` | — | 不设置，空串 |
| `user_type` | 固定 | `1` |
| `status` | 入参 | 默认 `0` |
| `token_version` | 固定 | `0` |
| `created_by` | 当前登录超管 id | 来自 `AuthInterceptor.ATTR_USER_ID` |

---

## 六、后端详细设计

### 6.1 改动清单

| # | 文件 | 动作 | 说明 |
|---|---|---|---|
| 1 | `doc/sql/p8-official-account.sql` | 新增 | `admin_user` 加 4 列 |
| 2 | `doc/sql/p9-app-user-password.sql` | 新增 | `app_user` 加 `password` 列 |
| 3 | `business/sys/entity/AdminUser.java` | 修改 | 补 4 个字段 |
| 4 | `business/appuser/entity/AppUser.java` | 修改 | 补 `password` 字段 |
| 5 | `common/util/JwtUtil.java` | 修改 | 新增带 `userType` 的签发重载 + 读取方法（见 6.2） |
| 6 | `business/auth/service/impl/AuthServiceImpl.java` | 修改 | 登录校验 `status`；`getUserInfo()` 按 `user_type` 返回角色 |
| 7 | `business/auth/vo/UserInfoVO.java` | 修改 | 建议补 `userType` 字段，供前端判断只读 |
| 8 | `common/interceptor/ReadOnlyInterceptor.java` | **新增** | 只读账号写请求拦截（见 6.4） |
| 9 | `common/config/WebConfig.java` | 修改 | 注册只读拦截器，order 在 `AuthInterceptor` 之后 |
| 10 | `business/sys/controller/AdminUserController.java` | **新增** | 官方账号管理接口（见 6.5） |
| 11 | `business/sys/service/AdminUserAdminService.java` + impl | **新增** | 官方账号业务逻辑 |
| 12 | `business/sys/dto/*.java` | **新增** | 创建/重置密码/改状态参数 |
| 13 | `business/appuser/dto/AppPasswordParam.java` 等 | **新增** | 设置/修改密码参数 |
| 14 | `business/appuser/controller/AppUserController.java` | 修改 | 追加设置/修改密码端点 |
| 15 | `business/appuser/service/AppUserService.java` + impl | 修改 | 追加密码设置/修改实现 |
| 16 | `business/appauth/controller/AppAuthController.java` | 修改 | 追加 `loginByPassword` |
| 17 | `business/appauth/service/AppAuthService.java` + impl | 修改 | 追加密码登录实现 |
| 18 | `common/api/ResultCode.java` | 可选 | 新增 `FORBIDDEN` 码（见 6.4 备注） |

### 6.2 JWT 增强（承载用户类型）

现状 `JwtUtil.createToken()` 的 payload 为 `aud / userId / type / ver / jti`，缺少用户类型。为避免只读拦截器每次请求查库，需要把 `user_type` 放进 token。

| 项 | 设计 |
|---|---|
| 新增 claim | `ut`（user type），取值 `0` / `1` |
| 新增签发重载 | `createAccessToken(userId, audience, version, userType)`、`createRefreshToken(userId, audience, version, userType)` |
| 新增读取方法 | `userType(token)`：取 `ut`，缺失或异常时返回 `0` |
| 兼容性 | 老 token 无 `ut` → `userType()` 返回 `0`（超管）。**这是安全的默认**：存量 admin 账号全部是超管 |
| refresh 时 | 必须透传 `ut`，否则刷新后只读账号会变成超管（**严重，需在测试中覆盖**） |
| 改动范围 | 只加方法，不修改现有 `createAccessToken(userId)` / `verifyAndGetUserId()`，存量调用零影响 |

> **关键风险**：`AuthServiceImpl.refreshToken()` 目前只传 `userId`。官方账号刷新 token 时必须从旧 token 读出 `ut` 再写回新 token，否则权限升级。列为必测项 T8。

### 6.3 官方账号生成算法

**格式**：`^cyt\d{4}$`，统一小写，范围 `cyt0000`–`cyt9999`，共 10000 个。

**算法（推荐：可用池随机，无重试循环）**

1. 查询 `admin_user` 中 `username LIKE 'cyt%'` 的已占用账号（只取 `username` 列，最多 10000 行，成本可忽略）。
2. 从 `0000`–`9999` 中剔除已占用，得到可用池。
3. 可用池为空 → 抛 `BizException("官方账号号段已用尽，请联系管理员")`。
4. 从可用池中随机取一个，拼成 `cyt` + 4 位数字（不足 4 位左侧补 `0`）。
5. 写入；若触发 `uk_username` 冲突（并发场景），最多重试 2 次回到步骤 1；仍失败则抛 `BizException("账号生成失败，请重试")`。

| 对比项 | 可用池随机（推荐） | 随机生成 + 重试 |
|---|---|---|
| 号池将满时的表现 | 稳定，直接命中剩余可用号 | 重试次数激增，可能失败 |
| 查询次数 | 固定 1 次（重试除外） | 每次重试 1 次查重 |
| 实现复杂度 | 略高（需取列表） | 低 |

**注意**：不要用 `SecureRandom` 直接生成后死循环重试——号池用到 90% 时单次成功率仅 10%，体验不可控。

### 6.4 只读拦截（核心安全机制）

新增 `common/interceptor/ReadOnlyInterceptor`。

| 项 | 设计 |
|---|---|
| 注册位置 | `WebConfig.addInterceptors()`，**order 必须在 `AuthInterceptor` 之后**（先鉴权再判权限） |
| 拦截范围 | `/**`（与 `AuthInterceptor` 对齐），同样排除 `/uploads/**`、`/app/**`、`/error` |
| 放行条件（任一即放行） | ① `OPTIONS` 预检<br>② `GET` / `HEAD` 请求<br>③ `jwtUtil.userType(token) != 1`（超管）<br>④ 路径在白名单内 |
| 白名单 | `/auth/login`、`/auth/refreshToken`（登录本身必须放行）。**其余全部拦截**，包含 `/auth/updatePassword`、`/auth/updateNickname`、`/auth/updateAvatar` |
| 拒绝响应 | HTTP 200 + `Result.failed("该账号为只读账号，无操作权限")`（code `1000`） |
| 获取 token | 从 `Authorization` 头解析；无 token 时直接放行（交由 `AuthInterceptor` 处理） |

**为什么不用「注解标记写接口」的白名单思路**：现有管理端写接口是 `POST` 语义分散（如 `/app-user/list` 是 POST 但只读），纯注解维护成本高且易漏。**按 HTTP 方法判定**更稳妥：所有 `POST/PUT/DELETE/PATCH` 一律拦截。

> **例外确认**：`POST /app-user/list`、`POST /admin-user/list` 等「POST 语义的查询接口」会被误拦。处理方式有两种，见待确认 D3 —— 推荐**给只读账号前端保留 POST 查询白名单**，或把列表接口改为 GET。

**可选增强**：在 `ResultCode` 新增 `FORBIDDEN("8886", "该账号为只读账号，无操作权限")`，便于前端统一识别并禁用所有操作按钮。本期默认用 `1000` + msg，侵入最小。

### 6.5 官方账号管理接口

Controller：`business/sys/controller/AdminUserController.java`，路径 `/admin-user/**`。

**权限**：仅超管可用。只读拦截器天然拦截（不在白名单），无需额外判断。

| 端点 | 说明 | 关键逻辑 |
|---|---|---|
| `POST /admin-user/create` | 创建官方账号 | 见 6.3 生成算法；BCrypt 存密码；`user_type=1`；记录 `created_by` |
| `POST /admin-user/list` | 官方账号列表 | 分页；条件：`user_type=1`；支持关键词（username/nickname）、status 筛选；**返回不含 password** |
| `POST /admin-user/resetPassword` | 重置密码 | 校验账号存在且 `user_type=1`；新密码 BCrypt；`token_version + 1`（踢下线） |
| `POST /admin-user/updateStatus` | 启用/禁用 | 仅允许 0/1；禁用时 `token_version + 1` 并写 Redis（见 6.8） |
| `POST /admin-user/delete` | 删除 | **建议不做**，用禁用代替（保留审计线索）。如必须做，需确认无外键依赖 |

**返回 VO**（新增 `AdminUserVO`，**严禁包含 `password`**）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 账号 id |
| `username` | string | 如 `cyt7391` |
| `nickname` | string | 展示名 |
| `status` | integer | 0 正常 / 1 禁用 |
| `userType` | integer | 固定 1 |
| `createdBy` | string | 创建人 id |
| `createTime` | string | `yyyy-MM-dd HH:mm` |
| `lastLoginTime` | string \| null | 需 `admin_user` 加列才支持，见待确认 D4 |

### 6.6 APP 用户设置/修改密码

Controller：`AppUserController`（`/app/user/**`，已有 `@AppLoginRequired`）。

| 端点 | 场景 | 逻辑 |
|---|---|---|
| `POST /app/user/password/set` | 首次设置 | ① 当前用户 `password` 必须为空，否则抛「密码已设置，请使用修改密码」<br>② 校验强度（见下）<br>③ BCrypt 存储<br>④ `token_version + 1` + 写 `ct:session:ver:{id}`（踢下线）<br>⑤ 返回成功 |
| `POST /app/user/password/update` | 修改密码 | ① 当前用户 `password` 必须非空，否则抛「尚未设置密码」<br>② `BCrypt.checkpw(旧密码)` 失败 → 「旧密码错误」<br>③ 新密码强度校验，且不能与旧密码相同<br>④ 同上的存储与踢下线 |

**密码强度（建议值，待确认 D2）**

| 规则 | 值 |
|---|---|
| 长度 | 8–20 位 |
| 字符 | 不能为纯数字；建议「字母 + 数字」组合，允许常见符号 |
| 非法 | 不能包含空格、不能与 QQ 号相同 |

**重要**：密码字段**不得**出现在任何 VO 中。`AppUserInfoVO` 无此字段，天然安全；**明确禁止**为「方便」在 VO 里加 `hasPassword` 以外的任何密码信息。`hasPassword` 若需要（前端展示「已设置/未设置」），只返回布尔值。

### 6.7 APP 用户密码登录

新增 `POST /app/auth/loginByPassword`（`AppAuthController`，**不加** `@AppLoginRequired`）。

| 步骤 | 逻辑 |
|---|---|
| 1 | 参数：`qqNumber`（必填，**账号**，**不做格式校验**：仅 `@NotBlank` + `@Size(max=64)`，能否登录一律由查库结果决定）、`password`（必填） |
| 2 | 先按 `app_user.qq_number` 查：命中 → 走 3–6 的 QQ 号流程 |
| 2.1 | 未命中 → 按 `admin_user.username + user_type=1` 查官方账号；查不到 → 「账号或密码错误」（**不区分账号是否存在，防账号枚举**） |
| 2.2 | 官方账号 `status != 0` → 「账号已被禁用」；`BCrypt.checkpw` 失败 → 同样计一次失败并抛「账号或密码错误」 |
| 2.3 | 官方账号登录成功：按官方账号名查绑定的社区身份 `app_user`，不存在则建档（`qq_number = username`、`email = username@official.local` 占位、昵称/头像取官方账号）；该身份 `status != 0` 同样拒绝登录 |
| 3 | `status != 0` → 「账号已被禁用」 |
| 4 | `password` 为 NULL → 「未设置密码，请使用验证码登录」 |
| 5 | `BCrypt.checkpw` **失败** → 计一次失败（见下方限流）并抛「账号或密码错误」 |
| 6 | 成功：清除该账号的失败计数；更新 `last_login_time`；复用 `AppAuthServiceImpl.result(user)` 签发 access/refresh token（与验证码登录**完全同构**） |

> **官方账号密码单一来源**：登录始终校验 `admin_user.password`，后台「重置密码」即时生效（含 APP 侧）；其社区身份 `app_user.password` 保持 NULL，APP 内「设置/修改密码」被拦截（「官方账号密码请在管理后台重置」），避免出现「APP 里改过却登录不上」的误导。

**限流（必须「失败后计数」，不能前置）**

`RedisHotspotService.allow()` 的底层脚本是 `INCR` 后与 `max` 比较，**每次调用都会累加**（脚本：`INCR key; if n==1 then PEXPIRE; return n`，返回 `n <= max`）。因此：

- ❌ **错误做法**：在校验密码前调用 `allow(...,5,...)` —— 正常用户 10 分钟内成功登录 6 次也会被锁。
- ✅ **正确做法**：仅当 `BCrypt.checkpw` 失败时调用 `allow("app-pwd-login", qqNumber, 5, Duration.ofMinutes(10))`，返回 `false` 即抛「尝试次数过多，请 10 分钟后再试」；登录成功时调用 `redis.evict("ct:rl:app-pwd-login:" + qqNumber)` 清零。

这样语义才是「10 分钟内最多 5 次**失败**」。

| 边界 | 行为 |
|---|---|
| Redis 不可用 | `allow()` 返回 `true` 自动放行，限流失效但登录可用（与项目既有降级一致） |
| 键过期 | `PEXPIRE` 在首次 `INCR` 时设置，10 分钟窗口自动滚动 |

**为什么登录账号用 QQ 号而不是邮箱**：`app_user.email` 无唯一索引，可能命中多条。若产品坚持支持邮箱登录，需先给 `email` 加唯一索引并清洗存量数据（列为待确认 D6）。

### 6.8 Token 失效机制（官方账号踢下线）

**问题**：`AuthInterceptor` 只校验 JWT 签名与有效期，**不查库**。因此：

- 官方账号被禁用后，已签发的 access token 最长仍可访问 2 小时。
- 超管改自己密码后，旧 token 仍可操作。

虽然官方账号只读，2 小时窗口风险相对可控，但「禁用不立即生效」是明显缺陷。

**推荐方案（与 APP 端对称）**

| 步骤 | 设计 |
|---|---|
| 1 | `admin_user` 加 `token_version`（已在 5.1 列出） |
| 2 | JWT 签发时写入 `ver`（`JwtUtil` 已支持 `createAccessToken(userId, aud, version, userType)`） |
| 3 | `AuthInterceptor` 增加校验：比对 token 的 `ver` 与 Redis `ct:admin:ver:{id}`；未命中则查 `admin_user` 并回写 Redis（TTL 10 分钟） |
| 4 | 禁用/重置密码时：`token_version + 1`，提交后写 `ct:admin:ver:{id}` |
| 5 | 同时校验 `status`：Redis 缓存 `ct:admin:status:{id}`，禁用后立即拦截 |

**成本与影响**：改动 `AuthInterceptor` 会影响**所有**管理端接口，需评估每请求一次 Redis 查询（命中缓存约 1ms）。若不接受，可接受 2 小时延迟作为降级（列为待确认 D8）。

> 若采纳，Redis 不可用时（`ciyuantown.redis.enabled=false`）按现有设计**放行**，与 `AppAuthInterceptor` 的降级行为保持一致。

---

## 七、接口汇总

### 7.1 新增接口

| # | 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/admin-user/create` | admin（超管） | 创建官方账号 |
| 2 | POST | `/admin-user/list` | admin（超管） | 官方账号列表 |
| 3 | POST | `/admin-user/resetPassword` | admin（超管） | 重置官方账号密码 |
| 4 | POST | `/admin-user/updateStatus` | admin（超管） | 启用/禁用官方账号 |
| 5 | POST | `/app/user/password/set` | app（登录） | 首次设置密码 |
| 6 | POST | `/app/user/password/update` | app（登录） | 修改密码 |
| 7 | POST | `/app/auth/loginByPassword` | 无 | QQ 号 + 密码登录 |

### 7.2 修改的接口

| 方法 | 路径 | 改动 |
|---|---|---|
| POST | `/auth/login` | 增加 `status` 校验（禁用拒绝登录）；token 携带 `ut` |
| POST | `/auth/refreshToken` | 透传 `ut`（**防止只读账号刷新后变超管**） |
| GET | `/auth/getUserInfo` | `roles` 按 `user_type` 返回；建议补 `userType` 字段 |

### 7.3 错误提示汇总

| 场景 | code | msg |
|---|---|---|
| 官方账号执行写操作 | 1000 | 该账号为只读账号，无操作权限 |
| 官方账号号段耗尽 | 1000 | 官方账号号段已用尽，请联系管理员 |
| 账号生成并发失败 | 1000 | 账号生成失败，请重试 |
| 官方账号被禁用后登录 | 1000 | 账号已被禁用 |
| 密码已设置仍调 set | 1000 | 密码已设置，请使用修改密码 |
| 未设置密码调 update | 1000 | 尚未设置密码 |
| 修改密码旧密码错 | 1000 | 旧密码错误 |
| 密码登录账号不存在/密码错 | 1000 | 账号或密码错误 |
| 密码登录但用户未设密码 | 1000 | 未设置密码，请使用验证码登录 |
| 密码登录失败超阈值 | 1000 | 尝试次数过多，请 10 分钟后再试 |
| 密码登录账号格式非法 | — | **无此校验（v2.3 起移除）**：官方账号名带字母，任何格式限制都会让「合法账号登录不了」，故密码登录不再限制账号格式 |
| 官方账号在 APP 内改密码 | 1000 | 官方账号密码请在管理后台重置 |
| 密码强度不足 | 1000 | 密码需为8-20位，且不能为纯数字 |

---

## 八、缓存与一致性

| 缓存 key | 处理 | 说明 |
|---|---|---|
| `ct:admin:ver:{id}` | 新增 | 官方账号/超管 token 版本（若采纳 6.8）；禁用、重置密码时写入，TTL 10 分钟 |
| `ct:admin:status:{id}` | 新增 | 管理员状态（若采纳 6.8）；禁用时写入 |
| `ct:session:ver:{id}` | 复用 | APP 用户改密码后踢下线（现成机制，直接复用 `AppAuthInterceptor` 的校验逻辑） |
| `ct:user:profile:{id}` | 无需处理 | 密码不进 profile 缓存；改密码无需清 profile |
| `ct:rl:app-pwd-login:{账号}` | 新增 | 密码登录失败限流（QQ 号与官方账号共用），由 `RedisHotspotService.allow()` 自动维护 |
| `ct:meta:*` | 无需处理 | 无关联 |

**Redis 不可用降级**：所有新增缓存均为「校验型」，Redis 不可用时按现有约定**放行**（与 `AppAuthInterceptor`、`RedisHotspotService` 一致），功能可用但踢下线/限流失效。

---

## 九、安全设计

| 项 | 措施 |
|---|---|
| 密码存储 | BCrypt（`BCrypt.hashpw(pwd, BCrypt.gensalt())`），与超管一致 |
| 密码传输 | 全站 HTTPS（部署层）；本期不做前端加盐/加密（复杂度收益低，HTTPS 已覆盖） |
| 密码泄露 | **任何 VO 均不含 `password`**；`AdminUserVO` / `AppUserInfoVO` 明确禁止扩展密码字段 |
| 账号枚举 | 密码登录对「账号不存在」与「密码错误」返回**相同**提示 |
| 暴力破解 | 密码登录限流（见 6.7） |
| 越权 | 只读拦截器按 HTTP 方法统一拦截，不依赖前端 |
| 权限升级 | refresh token 必须透传 `ut`（见 6.2，重点测试项） |
| 号段安全 | `cyt` 号段仅 10000，需防遍历爆破 → 登录限流同样覆盖官方账号；建议 `/auth/login` 也加限流 |
| SQL 注入 | 全程 `LambdaQueryWrapper` + `BaseMapper`，无拼接 SQL |
| 审计 | 创建/重置/禁用官方账号写 INFO 日志：操作人 id、目标账号 id、username；`created_by` 落库 |

---

## 十、前端配套契约

### 10.1 管理端 `ciyuantown-admin`

| # | 位置 | 改动 |
|---|---|---|
| 1 | `typings/api/auth.d.ts` | `UserInfo` 增加 `userType: number`（若后端采纳 7.2 建议） |
| 2 | 新增 `service/api/admin-user.ts` | 4 个官方账号接口；在 `service/api/index.ts` 追加 `export *` |
| 3 | 新增 `typings/api/admin-user.d.ts` | `AdminUser.ListItem`、`CreateParam`、`ResetPasswordParam`、`UpdateStatusParam`、`Page<T>` |
| 4 | 新增 `views/app-user-manage/modules/official-account.vue` | 官方账号「官方账号」页签内容：列表 + 创建弹窗 + 重置密码 + 启停。参照同页 APP 用户表格模式（v2.2 前为 `views/official-account-manage/index.vue` 独立页） |
| 5 | 路由 meta | **v2.2 起不再有独立路由**：侧栏入口删除，页签由 `app-user-manage` 承载；页签本身用 `v-if="!authStore.isReadOnly"` 对只读账号隐藏 |
| 6 | 按钮级隐藏 | 建议 `store/modules/auth` 暴露 `isReadOnly` 计算属性（`userInfo.roles.includes('R_OFFICIAL')`），各页面操作列用它 `v-if` |
| 7 | 只读提示 | 官方账号登录后，建议在顶部或用户菜单显示「只读账号」标识，避免困惑 |
| 8 | `views/profile/index.vue` | 已有 `v-for="role in authStore.userInfo.roles"` 展示角色，自动生效，无需改 |

> **页签可见性说明**：v2.2 后「官方账号」不再是独立路由，侧栏不出现该入口；页签由 `views/app-user-manage/index.vue` 用 `authStore.isReadOnly` 控制（`userInfo.userType === 1` 即官方只读账号），只读账号固定停留在「APP 用户」页签。`filterAuthRoutesByRoles`（`route.meta.roles` 为空则放行）仍作用于其余页面，因此官方账号**默认可见其他所有页面**（符合「可浏览」需求）。

### 10.2 APP 端 `次元町`

| # | 位置 | 改动 |
|---|---|---|
| 1 | `pages/settings/index.vue` 第 11–13 行 | 「通行证账号与安全」从 `openPlaceholder()` 改为跳转新页面 |
| 2 | 新增 `pages/settings/account-security/index.vue` | 密码状态展示（已设置/未设置）；未设置 → 「设置密码」表单；已设置 → 「修改密码」表单（旧密码 + 新密码 + 确认新密码） |
| 3 | 登录页 | 在验证码登录之外增加「账号密码登录」切换；调用 `/app/auth/loginByPassword` |
| 4 | 校验 | 前后端一致：8–20 位、非纯数字；两次输入一致 |
| 5 | `pages/login/index.vue` 账号输入框（v2.3） | 密码登录模式：标签「账号」、`:type="'text'"`、`:maxlength="20"`、校验 `^[0-9A-Za-z]{4,20}$`；验证码登录模式仍为 `type="number"`、上限 11、校验 `^[0-9]{5,11}$`（验证码发往 `{qq}@qq.com`，只支持 QQ 号） |
| 5 | 提示 | 未设置密码时登录密码，提示「未设置密码，请使用验证码登录」并引导去设置页 |

---

## 十一、测试用例

### 11.1 官方账号

| # | 场景 | 预期 |
|---|---|---|
| T1 | 创建官方账号 | 返回 `cyt` + 4 位数字；`user_type=1`；`status=0`；密码为 BCrypt 密文；`created_by` 正确 |
| T2 | 账号格式 | 正则 `^cyt\d{4}$` 匹配；不足 4 位补 0（如 `cyt0007`） |
| T3 | 连续创建 3 个 | 账号互不重复 |
| T4 | 并发创建 10 个 | 全部成功且无重复（验证唯一索引兜底与重试） |
| T5 | 号池耗尽 | 造满 10000 个后再创建 → 「官方账号号段已用尽」 |
| T6 | 官方账号登录管理面板 | `/auth/login` 成功；`getUserInfo` 返回 `roles=['R_OFFICIAL']` |
| T7 | 官方账号访问 GET 列表 | 正常返回数据 |
| T8 | **刷新 token** | 刷新后 `roles` 仍为 `R_OFFICIAL`，**未升级为 R_SUPER**（重点） |
| T9 | 官方账号调写接口（如 `/app-user/updateStatus`） | 拒绝，msg「该账号为只读账号，无操作权限」，数据未变更 |
| T10 | 官方账号调 `/admin-user/create` | 拒绝（只读拦截优先） |
| T11 | 官方账号改自己密码 `/auth/updatePassword` | 拒绝（按 6.4 白名单规则），见待确认 D9 |
| T12 | 官方账号可见菜单 | 侧栏无「官方账号管理」入口（该路由已删除）；用户管理页不出现「官方账号」页签；其他页面可见 |
| T13 | 禁用官方账号 | 再登录 → 「账号已被禁用」；若采纳 6.8，已登录 token 立即失效 |
| T14 | 重置官方账号密码 | 新密码可登录，旧密码不可登录 |
| T15 | 超管功能不受影响 | 超管所有写接口正常；`roles=['R_SUPER']` |
| T16 | 老 token 兼容 | 升级前签发的 token 仍可用且按超管处理（无 `ut` claim） |

### 11.2 APP 用户密码

| # | 场景 | 预期 |
|---|---|---|
| U1 | 未设置密码时查看「账号与安全」 | 显示「未设置密码」+ 设置入口 |
| U2 | 首次设置密码 | 成功；`app_user.password` 为 BCrypt 密文；`token_version` 递增 |
| U3 | 已设置再调 set | 拒绝「密码已设置，请使用修改密码」 |
| U4 | 未设置调 update | 拒绝「尚未设置密码」 |
| U5 | 修改密码旧密码错误 | 拒绝「旧密码错误」 |
| U6 | 修改密码成功 | 旧 token 失效（踢下线），需重新登录 |
| U7 | 密码登录（正确） | 登录成功，返回与验证码登录同构的 token |
| U8 | 密码登录（错误密码） | 「账号或密码错误」 |
| U9 | 密码登录（账号不存在） | 「账号或密码错误」，**提示与 U8 完全一致**（防枚举） |
| U10 | 未设置密码的用户走密码登录 | 「未设置密码，请使用验证码登录」 |
| U11 | 禁用用户密码登录 | 「账号已被禁用」 |
| U12 | 密码登录连续失败 6 次 | 前 5 次提示「账号或密码错误」，第 6 次起提示「尝试次数过多，请 10 分钟后再试」 |
| U17 | 成功登录 6 次（密码正确） | **不被锁定**（验证失败计数未前置累加） |
| U18 | 失败 3 次后成功登录 1 次 | 成功后计数清零，后续仍有 5 次失败额度 |
| U13 | 密码强度 | 纯数字、7 位、21 位均被拒 |
| U14 | 验证码登录回归 | 未设密码用户验证码登录正常；已设密码用户验证码登录**仍正常** |
| U15 | 接口返回不含密码 | 所有 `/app/user/*`、`/app/auth/*` 响应体无 `password` 字段 |
| U16 | Redis 关闭 | 限流不生效但登录功能正常（降级） |
| U19 | 官方账号密码登录 APP（正确） | 登录成功；自动建立 `app_user`（`qq_number = cyt####`、昵称为官方账号昵称）；返回 app token |
| U20 | 官方账号第二次登录 | 复用已建社区身份，不重复建档 |
| U21 | 官方账号密码错误 | 「账号或密码错误」，与 QQ 号错误提示一致；并计入 `ct:rl:app-pwd-login:{账号}` |
| U22 | 后台重置官方账号密码后再登录 APP | 旧密码失败、新密码成功 |
| U23 | 后台禁用官方账号 / 在用户管理禁用其社区身份 | APP 密码登录拒绝「账号已被禁用」 |
| U24 | 官方账号在 APP 内调 `password/set`、`password/update` | 拒绝「官方账号密码请在管理后台重置」 |
| U25 | 官方账号用 `cyt####` 走验证码登录 | 前端即拦截（账号框校验为 5-11 位数字），避免向 `cyt####@qq.com` 发信 |

---

## 十二、开发任务拆分

| # | 任务 | 涉及端 | 依赖 |
|---|---|---|---|
| 1 | `p8` / `p9` 迁移脚本 + 实体字段 | 后端 | — |
| 2 | `JwtUtil` 增加 `ut` claim（签发 + 读取 + refresh 透传） | 后端 | — |
| 3 | `AuthServiceImpl` 登录校验 status、`getUserInfo` 按类型返回角色 | 后端 | 1、2 |
| 4 | `ReadOnlyInterceptor` + `WebConfig` 注册 | 后端 | 2 |
| 5 | 官方账号 Service + Controller（创建/列表/重置密码/启停） | 后端 | 1 |
| 6 | 后端自测 T1–T16 | 后端 | 3、4、5 |
| 7 | APP 用户设置/修改密码 | 后端 | 1 |
| 8 | APP 密码登录 + 限流 | 后端 | 1 |
| 9 | 后端自测 U1–U16 | 后端 | 7、8 |
| 10 | 管理端「官方账号管理」页 + 类型/接口 | 管理端 | 6 |
| 11 | 管理端只读按钮隐藏 + 只读标识 | 管理端 | 6 |
| 12 | APP「通行证账号与安全」页 | APP 端 | 9 |
| 13 | APP 登录页密码登录切换 | APP 端 | 9 |
| 14 | 三端联调 + 全量回归 | 全部 | 11、12、13 |

> 建议：任务 1–9（后端）先跑通并用 curl/ApiFox 验证，再启动三端前端，避免前端空等。

---

## 十三、风险与待确认事项

### 13.1 风险

| 级别 | 风险 | 应对 |
|---|---|---|
| 高 | **refresh token 未透传 `ut`，只读账号刷新后变超管** | 6.2 明确要求透传；T8 重点覆盖 |
| 高 | 只读拦截遗漏 POST 语义的查询接口（如 `/app-user/list`） | 见待确认 D3；测试需遍历所有管理端 GET/POST 查询接口 |
| 中 | `cyt` 号段仅 10000，且可枚举 | 登录限流覆盖 `/auth/login`；号池耗尽有明确提示 |
| 中 | `AuthInterceptor` 改查库/Redis 影响全部管理端接口 | 6.8 方案需压测；或接受 2 小时延迟（D8） |
| 中 | `RedisHotspotService.allow()` **每次调用都 `INCR`**，若前置调用会把正常登录也计入，5 次成功即误锁 | 已确认脚本行为；6.7 明确规定「仅失败后计数 + 成功后清零」 |
| 低 | 存量老 token 无 `ut` claim | 默认按超管处理，存量账号均为超管，安全 |
| 低 | BCrypt 计算耗时（约 100ms） | 登录为低频操作，可接受 |

### 13.2 待确认

| # | 事项 | 建议 |
|---|---|---|
| D1 | 官方账号是否需要绑定 C 端社区账号（`admin_user.app_user_id`） | **v2.3 已落地（免加列方案）**：官方账号登录 APP 时惰性建档，绑定关系由 `app_user.qq_number = admin_user.username` 表达——因 APP 用户 QQ 号强制 5–11 位纯数字，`cyt####` 不会与之冲突。若后续并发量或审计要求更高，可再加 `admin_user.app_user_id` 列显式绑定 |
| D2 | 密码强度规则（8–20 位、非纯数字）是否合适 | 建议按此执行 |
| D3 | POST 语义的查询接口（`/app-user/list` 等）如何对只读账号放行 | **推荐**：维护一份只读白名单路径列表（约 4–6 个），比改 GET 更省事；或统一改为 GET |
| D4 | 官方账号是否需要「最后登录时间」 | 需要则给 `admin_user` 加 `last_login_time` 列 |
| D5 | 忘记密码如何找回 | 本期：C 端用户走验证码登录后重设；官方账号由超管重置 |
| D6 | 是否支持邮箱 + 密码登录 | 需先给 `app_user.email` 加唯一索引并清洗存量；建议本期只支持 QQ 号（v2.3 起密码登录账号已是 `[0-9A-Za-z]{4,20}`，扩展账号类型无需再改参数校验） |
| D7 | ~~限流计数时机~~ | **已确认**：`RedisHotspotService.allow()` 底层为 `INCR`（每次调用必累加）。已按「失败后计数 + 成功后 `evict` 清零」写入 6.7，无需再确认 |
| D8 | 官方账号禁用是否需要**立即**失效（改 `AuthInterceptor`） | 建议实施（安全），但可接受 2 小时延迟作为降级方案 |
| D9 | 官方账号能否修改自己的密码/昵称/头像 | 按 6.4 白名单规则**不能**。若产品要求允许，需在白名单追加这三个端点 |

---

## 附录：与 v1.0 方案的差异

v1.0 曾规划「管理员在后台创建 APP 用户」，该方案**已废弃**，差异如下：

| 项 | v1.0（废弃） | v2.0（现行） |
|---|---|---|
| 创建对象 | `app_user`（C 端用户） | `admin_user`（官方只读后台账号） |
| 账号名 | QQ 号 | `cyt` + 4 位随机数字 |
| 是否有密码 | 无（沿用验证码登录） | 有（BCrypt） |
| 能否进管理面板 | 否 | 能（只读） |
| C 端密码登录 | 无 | 新增（可选设置） |

> v1.0 中关于 `AppUserManageVO.from()` 对 `createTime` 未判空、`insert` 后需回查等结论仍然有效，但已不在本次改动范围内，后续若重开「后台创建 C 端用户」需求可直接复用。
