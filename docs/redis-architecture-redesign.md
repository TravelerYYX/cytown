# 次元町（ciyuantown）Redis 架构重设计方案

> 适用范围：后端 `ciyuantown-back`（Spring Boot + MyBatis-Plus + 无状态 JWT）。
> 目标：把目前**仅声明未使用**的 `spring-boot-starter-data-redis` 真正用起来，覆盖现有模块与尚未开发完善的帖子发布 / 点赞 / 收藏 / 评论。
> 约束：本方案为设计文档，**不改动任何现有业务代码**；落地时按「落地顺序」逐步接入，并提供 Redis 不可用时回退到现有 DB 实现的开关。

---

## 0. 现状与痛点（为什么需要 Redis）

| 能力 | 现状实现 | 痛点 |
|---|---|---|
| 验证码 | MySQL `verify_code` 表 + 256 分片 `synchronized` 锁 + `FOR UPDATE`；记录只增、无清理任务 | 高频发码时锁竞争；每次发码含当日 count 查询 + DB 写入，表行数持续膨胀 |
| 登录态 | 无状态 JWT（HS256，access 2h / refresh 7d，无 `jti`） | **无法主动吊销**：改密后旧 access/refresh 仍可用到自然过期；app 封禁即时生效依赖 `AppAuthInterceptor` 每次请求多查一次 DB（`selectById` 校验 status），admin 端（`AuthInterceptor`）则无任何吊销手段 |
| 点赞 / 收藏 | `user_like`/`user_favorite` 表 + `post.like_count`/`favorite_count`，`FOR UPDATE` 行锁 + JDBC 自增 | 热点帖（首页资讯）下写放大、锁等待、QPS 受限 |
| 评论计数 | `post.comment_count`、`comment.like_count` 字段已建但**无任何维护代码**（评论发布/点赞未实现，`CommentServiceImpl` 仅只读列表） | 帖子列表计数恒为空；落地发布/点赞时需防并发与写放大 |
| 帖子列表 / 详情 | 每次请求直查 MySQL（MyBatis-Plus） | 无缓存，首页/热门帖重复查询 |
| 首页配置 / 资讯轮播 / 通知 | 每次请求直查 DB | 读多写少场景未做缓存 |
| 关注关系 | `user_follow` 实体已存在但无实现 | 缺关系存储与 feed 基础 |

**结论**：Redis 当前是“死依赖”。重设计后定位为 **高速缓存 + 临时态/计数 + 实时关系索引**，MySQL 始终作为**持久化真相源**，Redis 与 DB 通过“异步落库 + 对账”保证最终一致。

---

## 1. Redis 角色与定位

```
                 ┌─────────────┐
   客户端 ───────▶│  Spring MVC │
                 └──────┬──────┘
        ┌───────────────┼───────────────────────┐
        ▼               ▼                        ▼
  ┌──────────┐   ┌──────────────┐        ┌──────────────┐
  │  Redis   │   │  Service 层  │        │   MySQL(DB)  │
  │缓存/计数/ │◀─▶│ 业务逻辑    │◀──────▶│  真相源      │
  │关系/限流  │   └──────┬───────┘        └──────────────┘
  └────┬─────┘          │
       │ 异步 flush     ▼
       └────────▶ 定时对账 / 消息队列 ──▶ 回写计数
```

- **缓存（Cache-Aside）**：首页配置、资讯轮播、通知列表、帖子列表/详情、评论页。
- **计数（Redis 主写 + DB 异步落库）**：点赞、收藏、评论、分享数。
- **实时关系（Set/ZSet）**：用户点赞集合、收藏集合、关注/粉丝、feed 时间线。
- **临时态/限流**：验证码、JWT 黑名单/会话、发码/发帖/评论频率限制。

---

## 2. Key 命名规范

统一前缀 `ct:`（ciyuantown）。建议开启 Redis `database` 分库隔离：

- `db0` 业务缓存（首页/通知/帖子/评论列表）
- `db1` 计数与关系（点赞/收藏/关注/feed）
- `db2` 会话/限流/验证码（短 TTL，可 `volatile-ttl` 淘汰）

> 注：多 database 仅适用于单实例/主从部署；若后续迁移 Redis Cluster（不支持跨库），需改为按 key 前缀分层（如 `ct:cache:` / `ct:cnt:` / `ct:tmp:`）替代分库，key 模板本身无需变更。

| 类别 | 命名模板 | 类型 | TTL |
|---|---|---|---|
| 验证码 | `ct:vc:{email}` | String | 5min |
| 发码频率 | `ct:vc:rl:send:{email}` | String(incr) | 60s |
| 每日上限 | `ct:vc:rl:day:{email}:{yyyyMMdd}` | String(incr) | 当日剩余秒 |
| JWT 黑名单 | `ct:jwt:bl:{jti}` | String("1") | token 剩余有效期 |
| Refresh 家族 | `ct:jwt:rt:{userId}` | String(版本号) | 7d |
| 会话(活跃 jti) | `ct:session:{userId}` | Set | 2h |
| 点赞集合 | `ct:like:set:{postId}` | Set(userId) | 永久 |
| 收藏集合 | `ct:fav:set:{postId}` | Set(userId) | 永久 |
| 点赞计数 | `ct:like:cnt:{postId}` | String(incr) | 永久(可回源) |
| 收藏计数 | `ct:fav:cnt:{postId}` | String(incr) | 永久 |
| 用户已赞帖 | `ct:like:user:{userId}` | Set(postId) | 永久 |
| 评论计数 | `ct:cmt:cnt:{postId}` | String(incr) | 永久 |
| 评论页缓存 | `ct:cmt:page:{postId}:{n}` | String(JSON) | 60s |
| 全局时间线 | `ct:feed:global` | ZSet(score=发布毫秒) | 永久 |
| 标签时间线 | `ct:feed:tag:{tagId}` | ZSet | 永久 |
| 关注流 | `ct:feed:follow:{userId}` | ZSet | 永久 |
| 关注中/粉丝 | `ct:fl:following:{userId}` / `ct:fl:followers:{userId}` | Set | 永久 |
| 关注数/粉丝数 | `ct:fl:cnt:following:{userId}` / `ct:fl:cnt:followers:{userId}` | String | 永久 |
| 首页配置 | `ct:home:config` | String(JSON) | 5min |
| 资讯轮播 | `ct:news:carousel` | String(JSON) | 5min |
| 通知列表 | `ct:notice:list:{catId|all}:{page}`（categoryId 可空＝全部） | String(JSON) | 2min |
| 帖子列表 | `ct:post:list:{scene}:{page}`（scene＝app 全部已发布 / 后台 keyword、status 等查询组合，区分大小写场景） | String(JSON) | 30s |
| 通用限流 | `ct:rl:{action}:{key}` | String(incr) | 窗口时长 |

---

## 3. 分模块设计

### 3.1 验证码（替代 MySQL `verify_code`）
- 发送：`INCR ct:vc:rl:send:{email}`，>1 且未过 60s 拒绝；`INCR ct:vc:rl:day:{email}:{yyyyMMdd}` 达 `daily-limit` 拒绝；生成 6 位码 `SET ct:vc:{email} code EX 300`。
- 校验：取 `ct:vc:{email}` 比对；可加 `ct:vc:try:{email}` 限制校验尝试次数防爆破；成功后 `DEL`。
- 优点：消除 `verify_code` 表与分片锁，发码/校验纯内存。
- 安全：验证码属敏感数据，Redis 仅用 `db2` 且短 TTL，不开启 AOF 持久化（或仅 RDB），避免落盘泄露。
- **落地状态（2026-09-08）**：开关开启时走 Redis 路径（`ct:vc:` 5min / `ct:vc:rl:send:` 60s / `ct:vc:rl:day:{yyyyMMdd}` 过期至次日零点，均短 TTL）；任一 Redis 操作抛 `DataAccessException` 即自动回退 DB `verify_code`（**表保留为降级存储，未删除**）；两路径对外语义对齐（60s 节流、每日上限、一次性消费、邮件失败整体回滚且可立即重试、错误文案不变）。本地 `.env` 已默认 `REDIS_ENABLED=true`（OS 环境变量可覆盖），日常运行/测试默认走 Redis；配套 `VerifyCodeRedisTests`（开关属性生效时执行，邮件 mock）与 P2 集成测试（properties 固定 `ciyuantown.redis.enabled=false`，专测 DB 路径，不受全局开关影响）。`ct:vc:try` 尝试次数限制未纳入（保持与现有 DB 语义一致）。测试门控与生产开关同源（读 `ciyuantown.redis.enabled` 属性，非裸 OS 环境变量）。

### 3.2 JWT 会话与令牌吊销（新增能力）
- 在 `JwtUtil` 的签发方法（`createAccessToken` / `createRefreshToken`；现有 claims：`aud`/`userId`/`type`）新增 `jti`（UUID）声明；`aud` 区分 `admin`/`app` 保持不变。
- 吊销触发点：admin 改密（`AuthServiceImpl.updatePassword`）、app 封禁（`AppUser.status` 变更）、登出（后端目前无登出接口，可新增 logout 拉黑当前会话；纯前端丢弃 token 亦可）。触发时 `SET ct:jwt:bl:{jti} 1 EX <剩余有效期>`，同时将该用户 refresh 家族版本 +1（见下），使旧 refresh 全部失效。
- 校验链路：`AppAuthInterceptor`/`AuthInterceptor` 解析 JWT 后 `EXISTS ct:jwt:bl:{jti}`，命中走现有「无效 token」失败分支（业务码 TOKEN_EXPIRED，触发前端自动刷新；刷新被家族版本拦截后按 UNAUTHORIZED 强制重新登录）。黑名单检查同时可替代 app 端每次请求的 `selectById` 封禁查询（DB 读 → Redis 读）。
- Refresh 家族：`SET ct:jwt:rt:{userId} {version}`，改密/封禁时版本 +1，旧 refresh token 校验失败 → 强制重新登录。
- 会话/踢下线（可选）：`SADD ct:session:{userId} {jti}`，单设备策略下新登录使旧会话失效。

### 3.3 点赞 / 收藏（核心改造，替代行锁计数）
**写流程（toggle，用 Lua 脚本保证原子）：**
1. `SADD/SREM ct:like:set:{postId} {userId}` → 返回是否新增（决定是否 +1/-1）
2. `INCRBY ct:like:cnt:{postId} ±1`
3. `SADD ct:like:user:{userId} {postId}` / `SREM`
4. 返回最新计数给前端（实时，无 DB 往返）

**异步落库：** toggle 事件（userId、postId、type、±1）累积后由定时任务（`@Scheduled` 或 MQ）批量回写 DB：关系行 `INSERT/DELETE user_like|user_favorite`（持久层与 Redis 集合保持同构，作为回源与后台统计基础）+ `UPDATE post SET like_count/favorite_count=...`；删除/下架帖子时强一致 flush 一次后再删。

**读流程（详情页 `fillState`）：**
- 是否点赞/收藏：`SISMEMBER ct:like:set:{postId} {userId}`（O(1)）
- 计数：`GET ct:like:cnt:{postId}`；未命中以 `user_like/user_favorite` 表行数 COUNT 为基准回源并回填（Redis 是派生快速通道，DB 关系表才是回源真相，避免与批量落库互相覆盖出错）。

**一致性：** 定时 reconcile 任务以 `user_like`/`user_favorite` 关系表行数为基准，与 Redis 集合、Redis 计数、`post.*_count` 三方比对并校正（集合漂移重建、计数回填），保证最终一致。

### 3.4 评论（发布 + 计数 + 列表缓存）
- 发布：评论落 MySQL `comment`（两级 `parentId`、`pinned`、`likeCount`）；随后：
  - `INCR ct:cmt:cnt:{postId}`
  - `DEL ct:cmt:page:{postId}:*` 与 `ct:post:list:*`（失效帖子侧缓存）
- 评论点赞（后续）：`ct:clike:set:{commentId}` + `ct:clike:cnt:{commentId}`，模式同 3.3。
- 列表缓存：`ct:cmt:page:{postId}:{n}` 缓存序列化页（TTL 60s），写评论即失效。
- 防刷：`INCR ct:rl:comment:{userId}`，窗口内超限拒绝。

### 3.5 帖子发布（尚未完善部分）
**状态机：** `post.status` 0 草稿 / 1 已发布 / 2 下架。
**发布流程：**
1. 事务写入 `post` + `post_media` + `post_tag`。
2. 预热时间线：
   - `ZADD ct:feed:global {publishTimeMs} {postId}`
   - 按标签 `ZADD ct:feed:tag:{tagId} ...`
   - 若采用“写扩散”：`ZADD ct:feed:follow:{followerId} ...`（遍历作者粉丝）
3. 初始化计数：`SET ct:like:cnt:{postId} 0` 等（避免首读回源 miss）。
4. `DEL ct:post:list:*`、`DEL ct:news:carousel`（如 `homeNewsEnabled`）。

**Feed 流选型：**
- **早期（用户量小、单实例）**：读扩散 + 缓存。首页列表 `ct:post:list:{scene}:{page}`（TTL 30~60s），发布/状态变更时 DEL。简单、存储省。
- **成长期**：关注流写扩散 ZSet（`ct:feed:follow:{userId}`），发布时推给粉丝，读时 `ZREVRANGE` 取时间线 + 再查帖子详情（详情可走缓存）。给出切换点：粉丝均值 > 阈值或写峰明显时切换。

### 3.6 关注关系（UserFollow）
- `SADD ct:fl:following:{userId} {followeeId}` / `ct:fl:followers:{followeeId} {userId}`
- `INCRBY ct:fl:cnt:following/followers ±1`
- 取关反向操作；支持“共同关注”“是否互关”用集合交并。
- 作为 3.5 写扩散 feed 的关系基础。

### 3.7 首页配置 / 资讯轮播 / 通知缓存
- 首页配置 `ct:home:config`：缓存 `HomeConfigServiceImpl.appConfig()` 整包结果（频道 `home_channel` + 快捷入口 `home_entry` + 资讯开关/轮播间隔/空文案 `system_config.news_*`）；资讯轮播 `ct:news:carousel`：`post.homeNewsEnabled=true && status=1` 的资讯项（如仅 id+title，滚动只读接口高频）。后台变更（`HomeConfigService` 保存频道/入口/资讯开关；`PostService.updateStatus`/`updateHomeNews`；新帖发布置为资讯）时 DEL 或主动 SET，TTL 5min。
- 通知列表 `ct:notice:list:{catId|all}:{page}`：发布/编辑/删除/上下架通知时按分类（或全部分类）DEL，TTL 2min。
- 通过自定义 `RedisCacheManager`（统一 TTL、空值防穿透、Key 前缀）配合 `@Cacheable` 落地；注意当前 `pom.xml` 未引入 `spring-boot-starter-cache`，落地第 1 步需一并添加（Caffeine 二级缓存亦需引入依赖）。

### 3.8 全局限流 / 防刷
- 登录、发码、发帖、评论统一 `ct:rl:{action}:{key}` 固定窗口计数器；平滑需求用 ZSet 滑动窗口。
- 验证码尝试次数、接口级 QPS 均走此机制。

---

## 4. 数据一致性策略（Redis ↔ MySQL）

- **计数**：Redis 主写，DB 异步对账落库（reconcile 定时任务 + 发布/删除时强一致 flush）。
- **缓存**：Cache-Aside——更新 DB 后 `DEL` 缓存（避免双写不一致），TTL 加随机抖动防雪崩。
- **防穿透**：空值缓存（短 TTL）+ 标签/存在性布隆过滤器。
- **防雪崩**：TTL 加随机抖动；热点 key 本地（Caffeine）二级缓存兜底。
- **防击穿**：热点 key 互斥重建（`SETNX` 锁）或逻辑过期。

---

## 5. 部署与配置建议

`application.yml` 增加（注意：项目为 **Spring Boot 3.x**，命名空间为 `spring.data.redis`，非 Boot 2 的 `spring.redis`）：
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      # db0 业务缓存 / db1 计数与关系 / db2 会话·限流·验证码；
      # 多库需按业务分别定义 ConnectionFactory（Cluster 下改走 key 前缀分层，见 §2 注记）
      database: ${REDIS_DATABASE:0}
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0
          max-wait: 3000ms
ciyuantown:
  redis:
    # 功能总开关（§6 落地顺序各步的业务降级统一以此为准）
    enabled: ${REDIS_ENABLED:false}
```
- 客户端：Lettuce（默认，连接池友好、线程安全）。
- 淘汰策略：缓存库 `allkeys-lru`；验证码/限流库 `volatile-ttl`。
- 序列化：Key=`StringRedisSerializer`，Value=`Jackson2JsonRedisSerializer`（或 String 存 JSON）。
- 生产建议：主从 + 哨兵，或 Redis Cluster；开启 RDB 周期快照（计数库可关闭 AOF）。

---

## 6. 落地顺序（不改动现有代码、可回退）

1. 新增 `RedisConfig`：`LettuceConnectionFactory` + `RedisTemplate` + `RedisCacheManager`（feature flag `ct.redis.enabled` 控制启用）。**基础版已于 2026-09-08 落地**：`pom.xml` 补 `spring-boot-starter-cache` + `commons-pool2`；`application.yml` 增加 `spring.data.redis.*` 与 `ciyuantown.redis.enabled`（默认 false）；新增 `RedisConfig` 条件装配（开启时 `RedisTemplate`(String，`@Primary`，覆盖 Boot 默认 JDK 序列化模板) + `RedisCacheManager`(ct: 前缀/JSON/TTL 5min/禁空值) + `@EnableCaching`，关闭时零 Bean、行为与历史一致），配套条件装配测试 `RedisConfigTest`。**真实 Redis 连通性联调已完成（2026-09-08）**：`RedisConnectivityTest`（仅 `REDIS_ENABLED=true` 时执行，否则自动跳过）对本地 `redis:7-alpine` 容器实例完成 `RedisTemplate` set/get/delete 与 `RedisCacheManager` put/get/evict 验证；`@Primary` 用于消除与 Boot 自带 `StringRedisTemplate` 的类型注入二义。
2. 验证码迁 Redis（删除 `verify_code` 表依赖路径）。**已于 2026-09-08 落地（含自动降级）**：`EmailCodeServiceImpl` 双路径改造——Redis 短 TTL 临时态优先、任何 `DataAccessException`/开关关闭回退 DB 原实现（`verify_code` 表保留为降级存储）；配套 `VerifyCodeRedisTests` 与真机登录冒烟（预置码登录 0000 / 复用 1000 / key 消费即删）。
3. 点赞/收藏计数迁 Redis + 异步落库 + reconcile。
4. 评论计数与列表缓存 + 防刷。
5. 帖子发布流程 + feed 时间线。
6. JWT 吊销 + 会话/踢下线。
7. 首页/通知缓存 + 全局限流。

> 每步提供「Redis 降级开关」：不可用时回退现有 DB 行锁/直查实现，保证灰度安全。

---

## 7. 风险与回滚

- 计数异步落库需 reconcile 任务兜底，防止 Redis 宕机丢计数（提高 flush 频率或开启计数库 AOF）。
- 提供统一 `RedisFallback` 抽象：所有 Redis 操作包裹 try-catch，异常时走 DB 实现，避免缓存故障拖垮主链路。
- Key 设计预留分库与命名空间，便于后续按业务隔离与监控（Redis Insight / Prometheus exporter）。

---

## 8. Key 速查表（开发时对照）

| 场景 | 命令示例 |
|---|---|
| 发码频率 | `INCR ct:vc:rl:send:{email}` + `EXPIRE 60` |
| 存验证码 | `SET ct:vc:{email} {code} EX 300` |
| 校验码 | `GET ct:vc:{email}` → 比对 → `DEL` |
| 点赞 | `SADD ct:like:set:{postId} {userId}` + `INCRBY ct:like:cnt:{postId} 1` |
| 取消赞 | `SREM ct:like:set:{postId} {userId}` + `INCRBY ct:like:cnt:{postId} -1` |
| 是否点赞 | `SISMEMBER ct:like:set:{postId} {userId}` |
| 取赞数 | `GET ct:like:cnt:{postId}`（miss 回源 DB） |
| 评论数 | `INCR ct:cmt:cnt:{postId}` |
| 发布入流 | `ZADD ct:feed:global {ms} {postId}` |
| 关注 | `SADD ct:fl:following:{u1} {u2}` + `SADD ct:fl:followers:{u2} {u1}` |
| 吊销 token | `SET ct:jwt:bl:{jti} 1 EX {剩余秒}` |
| 首页配置 | `GET ct:home:config`（miss → 查 DB → `SET EX 300`） |
| 限流 | `INCR ct:rl:comment:{userId}` + `EXPIRE 60` |
