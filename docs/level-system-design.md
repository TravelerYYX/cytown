# 社区等级系统开发文档

> 主题风格：原神 · 星穹铁道
>
> | 项目 | 内容 |
> |---|---|
> | 文档版本 | v1.1（v1.0 初稿；v1.1 纳入 Redis 核心设计，对齐 `redis-architecture-redesign.md`） |
> | 编写日期 | 2026-09-08 |
> | 状态 | 待评审（含待确认事项，见[第十二节](#十二待确认事项)） |
> | 涉及端 | 后端 `ciyuantown-back`、小程序/H5 端 `次元町`、管理端 `ciyuantown-admin`（可选） |

---

## 目录

1. [功能概述](#一功能概述)
2. [等级体系](#二等级体系)
3. [经验获取规则](#三经验获取规则)
4. [整体设计](#四整体设计)
5. [数据库设计](#五数据库设计)
6. [后端详细设计](#六后端详细设计)
7. [接口设计](#七接口设计)
8. [前端设计（uni-app）](#八前端设计uni-app)
9. [管理后台（可选）](#九管理后台可选)
10. [测试用例](#十测试用例)
11. [开发任务拆分](#十一开发任务拆分)
12. [待确认事项](#十二待确认事项)
13. [附录一：数值校验](#附录一数值校验)
14. [附录二：Redis 命令速查](#附录二redis-命令速查)

---

## 一、功能概述

为社区新增用户成长等级体系，用户通过**每日签到、评论、发帖**等行为获取经验值（EXP），经验累计提升等级，等级对应一个星穹铁道风格头衔。等级用于身份标识与荣誉展示（本期不涉及权限/权益变更）。

**名词约定**

| 名词 | 含义 |
|---|---|
| 经验（EXP） | 用户成长数值，只增不减（本期） |
| 等级（Level） | 由累计经验推导，范围 1 ~ 7，只升不降 |
| 头衔（Title） | 等级的展示名称，如「初旅」「归穹」 |
| 连续签到天数 | 从最近一次中断日开始、不缺卡地签到到今天的自然日天数 |
| 自然日 | 以 **Asia/Shanghai（UTC+8）** 时区的 00:00:00 为界，不依赖服务器默认时区 |

---

## 二、等级体系

### 2.1 头衔总览

```
初旅 → 循迹 → 逐曜 → 织星 → 执命 → 耀令 → 归穹
 Lv.1   Lv.2   Lv.3   Lv.4   Lv.5   Lv.6   Lv.7
```

### 2.2 等级配置表

| 等级 | 头衔 | 累计总经验（达到即升级） | 本级升级所需经验 | 区间 [下限, 上限) |
|:---:|:---:|:---:|:---:|:---:|
| 1 | 初旅 | 0 | —（初始等级） | [0, 200) |
| 2 | 循迹 | 200 | 200 | [200, 800) |
| 3 | 逐曜 | 800 | 600 | [800, 2200) |
| 4 | 织星 | 2200 | 1400 | [2200, 5000) |
| 5 | 执命 | 5000 | 2800 | [5000, 10000) |
| 6 | 耀令 | 10000 | 5000 | [10000, 18000) |
| 7 | 归穹 | 18000 | 8000 | [18000, ∞) 满级 |

### 2.3 判定规则

- 等级由**累计经验**唯一推导：找到满足 `累计经验 >= 阈值` 的最高等级。
- 经验只增不减，因此等级**只升不降**；每次经验变动后重算一次即可。
- 后端以常量/枚举维护阈值（7 级固定，无需建配置表），前端同一份阈值用于进度条展示，**前后端必须引用同一数值源**（后端接口下发为准）。

```java
public final class LevelConst {
    /** 等级阈值，下标 i 对应等级 i+1 的累计经验门槛 */
    public static final int[] EXP_THRESHOLDS = {0, 200, 800, 2200, 5000, 10000, 18000};
    public static final String[] TITLES = {"初旅", "循迹", "逐曜", "织星", "执命", "耀令", "归穹"};

    public static int levelOf(int exp) {
        for (int i = EXP_THRESHOLDS.length - 1; i >= 0; i--) {
            if (exp >= EXP_THRESHOLDS[i]) return i + 1;
        }
        return 1;
    }
}
```

---

## 三、经验获取规则

### 3.1 行为经验表

| 行为 | 经验值 | 限制 | 动作编码 |
|:---|:---:|:---|:---|
| 每日签到 | +10 | 每日 1 次 | `CHECKIN` |
| 连续 7 天签到奖励 | +50 | 每 7 天 1 次 | `CHECKIN_BONUS` |
| 发表评论 | +3 | 每日经验上限 15 | `COMMENT` |
| 发布普通帖子 | +15 | 暂无上限（见防刷建议） | `POST` |

### 3.2 规则细则

**① 每日签到（+10）**
- 每个自然日仅首次点击有效，重复请求幂等返回当日结果。
- 签到即算当日，不需要固定时段。

**② 连续签到奖励（+50）**
- 连续签到第 **7** 天的当天，在 +10 之外额外发放 +50。
- 「每周一次」按**滑动周期**理解：连续不中断的前提下，第 7、14、21… 天各发放一次（即每满 7 个连续自然日一次）。
- **断签即清零**：某自然日未签到，则连续天数归零，下次签到重新从第 1 天计起。
- 奖励发放以 `sign_record` 表的实际连续天数判定，不依赖前端传参。

**③ 发表评论（+3）**
- 上限按「**每日评论经验上限 15 点**」设计，即每天前 **5** 条评论获得经验（5 × 3 = 15），第 6 条起不再发放（接口正常，仅无经验）。
- 经验发放与评论本身的成功解耦：评论成功但当日已达上限时，只写一条 `exp = 0` 的流水或直接跳过（二选一，见待确认事项 5，默认跳过不写流水）。
- 评论被删除/审核不通过**不扣回**经验（本期经验只增不减）。

**④ 发布普通帖子（+15）**
- 按帖子**发布成功**即发放；若帖子走「先审后发」，建议改为**审核通过时发放**（见待确认事项 4）。
- 目前无上限，存在刷分风险，防刷建议见 3.4。

### 3.3 每日经验上限汇总

| 场景 | 签到 | 连签奖励 | 评论 | 发帖 | 单日合计 |
|:---|:---:|:---:|:---:|:---:|:---:|
| 普通日 | 10 | — | 15 | 15 | **40** |
| 连签第 7/14/21… 天 | 10 | 50 | 15 | 15 | **90** |

> 与最初需求对比：评论按「经验上限 15」口径时每日上限为 40/90；若「上限 15」意为「15 条评论」，则每日上限为 70/120。以最终确认口径为准（待确认事项 1）。

### 3.4 防刷说明

| 风险点 | 措施 |
|---|---|
| 签到重复请求 | `sign_record` 唯一索引 `(user_id, sign_date)` 幂等 |
| 评论/发帖重复触发经验 | `exp_log` 唯一索引 `(user_id, action, biz_id)` 幂等 |
| 短时间刷评论 | 每日经验上限 + 复用现有评论频控 |
| 无上限刷帖子得经验 | 建议二选一：① 每日发帖经验上限（如前 3 帖）；② 帖子审核通过后才发放经验（推荐，与内容治理一致） |
| 接口重放 | 经验发放全部走服务端判定，前端参数不参与经验计算 |

---

## 四、整体设计

### 4.1 模块位置

新建业务模块 `com.yyx.ciyuantown.business.level`，按项目现有分层：

```
com.yyx.ciyuantown.business.level
├── controller
│   ├── AppLevelController.java        // 小程序/H5 端：/app/level
│   └── LevelManageController.java     // 管理端（可选）：/level
├── service
│   ├── ExpService.java                // 经验发放（供其他模块调用）
│   ├── SignService.java               // 签到
│   ├── LevelQueryService.java         // 等级/流水查询
│   └── impl/...
├── entity
│   ├── UserLevel.java
│   ├── ExpLog.java
│   └── SignRecord.java
├── mapper
│   ├── UserLevelMapper.java
│   ├── ExpLogMapper.java
│   └── SignRecordMapper.java
├── dto
│   └── ExpGrantParam.java
└── vo
    ├── UserLevelVO.java               // 等级+头衔+进度
    ├── SignStatusVO.java              // 签到状态
    └── ExpLogVO.java
```

### 4.2 耦合方式

```
┌─────────────┐   发评论成功    ┌──────────────┐
│ post 模块    │ ─────────────▶ │              │──▶ MySQL（user_level/exp_log/sign_record）真相源
│ (Comment)    │   发帖成功      │  level 模块   │
└─────────────┘ ─────────────▶ │  Exp/Sign     │──▶ Redis（签到位图/连续天数/每日额度/缓存）
      签到请求 ───────────────▶ │  Service      │     ↺ 定时对账（位图↔sign_record、计数↔exp）
                                └──────────────┘
```

- `CommentServiceImpl` / `PostServiceImpl` 在**业务事务提交后**调用 `ExpService.grant(...)`；推荐用 Spring 事件（`ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)`）解耦，level 模块不反向依赖 post 模块。
- 经验发放失败（如 Redis 异常）**不影响**评论/发帖主流程，记录告警日志 + 可补偿重试。

### 4.3 数据流向原则（与 redis-architecture-redesign.md 对齐）

- **MySQL 是真相源**：`user_level` / `exp_log` / `sign_record` 为唯一事实来源；签到与经验在请求内**同步落库**（每用户每日低频写，DB 无压力，经验作为用户资产不做异步化）。
- **Redis 承担四类职责**：
  1. **签到判重与日历**：月度位图 `SETBIT/GETBIT`，O(1) 幂等判断、一条命令取整月签到情况；
  2. **连续天数**：独立 key 计数，TTL 到「后天 0 点」，断签自然过期归零；
  3. **每日经验额度**：`INCR` 计数器替代每次评论/发帖前的 `SUM(exp_log)` 扫描；
  4. **缓存与排行**：等级信息 Cache-Aside、累计经验镜像与可选排行榜。
- **双路径降级**：沿用验证码的落地模式——`ciyuantown.redis.enabled` 总开关 + 任何 `DataAccessException` 自动回退纯 DB 路径，两路径对外语义完全一致。
- **对账兜底**：Redis 位图/计数丢失均可由 DB 表懒重建；定时对账以 DB 为准校正 Redis。

---

## 五、数据库设计

> 表名、字段风格请与现有表（如 `app_user`、`post`、`comment`）保持一致；若项目有逻辑删除/雪花 ID 等统一约定，按约定调整。

### 5.1 用户等级表 `user_level`

```sql
CREATE TABLE `user_level` (
  `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`              BIGINT       NOT NULL COMMENT '用户ID',
  `exp`                  INT          NOT NULL DEFAULT 0 COMMENT '累计经验',
  `level`                TINYINT      NOT NULL DEFAULT 1 COMMENT '当前等级 1~7',
  `total_sign_days`      INT          NOT NULL DEFAULT 0 COMMENT '累计签到天数',
  `continuous_sign_days` INT          NOT NULL DEFAULT 0 COMMENT '当前连续签到天数',
  `last_sign_date`       DATE         NULL COMMENT '最近签到日期',
  `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户等级扩展表';
```

- 独立建表而非在 `app_user` 上加列，避免污染用户主表；如偏好加列也可，但签到相关字段仍建议单独放。
- `level` 为冗余字段（可由 `exp` 推导），落库用于列表页排序与后台查询，**每次经验变动时同步更新**。

### 5.2 经验流水表 `exp_log`

```sql
CREATE TABLE `exp_log` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT      NOT NULL COMMENT '用户ID',
  `action`      VARCHAR(32) NOT NULL COMMENT '动作: CHECKIN/CHECKIN_BONUS/COMMENT/POST',
  `exp`         INT         NOT NULL COMMENT '本次经验值(>0)',
  `biz_id`      BIGINT      NOT NULL DEFAULT 0 COMMENT '业务ID: 签到=yyyyMMdd / 评论=commentId / 帖子=postId',
  `remark`      VARCHAR(64) NULL COMMENT '展示文案，如 连续签到7天奖励',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idempotent` (`user_id`, `action`, `biz_id`),
  KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '经验流水表';
```

- `uk_idempotent` 是**防重复发放的核心**：签到用日期做 `biz_id`，评论/帖子用自身 ID，天然幂等；重复插入报 `DuplicateKey` 时按「已发放」处理。
- 同时承载「每日上限」统计：`SELECT COALESCE(SUM(exp),0) FROM exp_log WHERE user_id=? AND action='COMMENT' AND create_time >= 今日0点`。

### 5.3 签到记录表 `sign_record`

```sql
CREATE TABLE `sign_record` (
  `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         BIGINT   NOT NULL COMMENT '用户ID',
  `sign_date`       DATE     NOT NULL COMMENT '签到自然日',
  `continuous_days` INT      NOT NULL COMMENT '本次签到后的连续天数',
  `bonus`           TINYINT  NOT NULL DEFAULT 0 COMMENT '是否触发连签奖励 0/1',
  `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_date` (`user_id`, `sign_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '每日签到记录表';
```

- 连续天数由 `user_level.last_sign_date` + `continuous_sign_days` 推导，本表同时是 Redis 位图/连续天数的**重建基准**（日历展示、审计、对账都用它）。

### 5.4 存量用户初始化

```sql
INSERT INTO user_level (user_id) SELECT id FROM app_user;  -- 字段名按实际调整
```

- 新注册用户在注册事务内（或首次查询时懒创建）插入 `user_level` 默认行：Lv.1 / 0 经验。

---

## 六、后端详细设计

### 6.1 核心服务接口

```java
public interface ExpService {
    /**
     * 发放经验（幂等）。bizId 用于幂等键，重复调用不重复发放。
     * @return 实际发放的经验值（重复/超上限返回 0）
     */
    int grant(Long userId, ExpAction action, long bizId);
}

public interface SignService {
    /** 签到：返回本次获得经验（10 / 60）及是否升级 */
    CheckinResultVO checkin(Long userId);
    SignStatusVO status(Long userId);
}
```

`ExpAction` 枚举携带经验值与每日上限：

```java
public enum ExpAction {
    CHECKIN(10, 1, "每日签到"),
    CHECKIN_BONUS(50, 1, "连续7天签到奖励"),
    COMMENT(3, 5, "发表评论"),        // 上限=可获经验次数：15经验 ÷ 3 = 5 次
    POST(15, Integer.MAX_VALUE, "发布帖子");
}
```

### 6.2 Redis 总体设计（对齐 redis-architecture-redesign.md）

**定位**：MySQL 仍是等级/经验/签到的**真相源**；Redis 承担高速判重、日历查询、每日额度与缓存。沿用既有约定：`ct:` key 前缀、db0 缓存 / db1 计数与关系 / db2 限流临时态、`ciyuantown.redis.enabled` 总开关、Redis 异常自动回退 DB 的双路径模式（与验证码落地方式一致）。

**Key 设计**

| 类别 | Key 模板 | 类型 | TTL | 库 | 说明 |
|---|---|---|---|---|---|
| 签到位图 | `ct:sign:bits:{userId}:{yyyyMM}` | Bitmap | 永久¹ | db1 | 每月 1 个 key，每日 1 bit（偏移 = 自然日 1~31），判重与日历查询基础 |
| 连续天数 | `ct:sign:streak:{userId}` | String(incr) | 至后天 0 点 | db1 | 断签自动过期归零；异常丢失可由 DB 恢复 |
| 每日经验额度 | `ct:exp:day:{userId}:{action}:{yyyyMMdd}` | String(incr) | 次日 0 点 | db2 | 当日该动作已获经验的**次数**，替代逐次 `SUM(exp_log)` |
| 等级信息缓存 | `ct:level:info:{userId}` | String(JSON) | 5min | db0 | Cache-Aside，经验变动后 DEL |
| 经验镜像 | `ct:exp:cnt:{userId}` | String(incr) | 永久(可回源) | db1 | 累计经验快速读，供排行榜与对账 |
| 经验排行榜（可选） | `ct:rank:exp` | ZSet | 永久 | db1 | member=userId、score=exp |

> ¹ 位图每月仅约 4 字节，设为永久；丢失时由 `sign_record` 回放重建，无需为省空间设 TTL。

**签到 Lua 脚本（判重 + 记位 + 连续天数，原子执行）**

```lua
-- KEYS[1] 本月位图      KEYS[2] 昨日所属位图（同月时与 KEYS[1] 相同，跨月传上月位图）
-- KEYS[3] 连续天数 key
-- ARGV[1] 今日偏移(1~31)   ARGV[2] 昨日偏移(1~31；昨天未签过/无记录传 0)   ARGV[3] streak TTL 秒
-- 返回：-1 = 今日已签；>=1 = 本次签到后的连续天数
if redis.call('GETBIT', KEYS[1], ARGV[1]) == 1 then
  return -1
end
redis.call('SETBIT', KEYS[1], ARGV[1], 1)
local streak
if ARGV[2] ~= '0' and redis.call('GETBIT', KEYS[2], ARGV[2]) == 1 then
  streak = redis.call('INCR', KEYS[3])     -- 昨天签过 → 连续 +1
else
  redis.call('SET', KEYS[3], 1)            -- 首签 / 断签 → 重新计数
  streak = 1
end
redis.call('EXPIRE', KEYS[3], tonumber(ARGV[3]))
return streak
```

要点：

- Lua 原子完成「判重 → 记位 → 连续天数」，并发双签与重复奖励在 Redis 层即被拦截；脚本建议放 `resources/lua/sign_checkin.lua`，用 `DefaultRedisScript` 加载——Spring Data Redis 会自动走 `EVALSHA` 并在脚本未缓存（NOSCRIPT）时回退 `EVAL`；项目 RedisConfig 中现成的 String 序列化 `RedisTemplate` 恰好匹配脚本的 key/args 序列化要求。
- **streak key TTL 语义**：签到后存活到「后天 0 点」——今天签了、明天没签，key 在后天零点过期；明天补签则续期。天然实现「断签归零」，无需扫描判断。
- **异常恢复**：若 streak key 缺失但昨日位为 1（被驱逐/丢失），**不按 1 重置**——回源 `user_level`（`last_sign_date=昨天` 时取 `continuous_sign_days+1` 修复）；整月位图丢失由 `sign_record` 懒重建（批量 `SETBIT` 回放）。
- **跨月**：昨日属于上月时，KEYS[2] 传上月位图，连续性跨月不断。
- **Cluster 兼容**：脚本一次涉及 3 个 key。建议真实 key 中保留 `{userId}` 的花括号（如 `ct:sign:bits:{u123}:202609`、`ct:sign:streak:{u123}`）——花括号即 Redis Cluster 的 hash tag，三个 key 恒落同一 slot；单实例/主从部署下无任何影响，未来迁 Cluster 零改动。
- DB 路径（降级 / 真相落库）与 Redis 路径判定语义完全一致，判定基准最终都落回 `sign_record` / `user_level`。

**对账任务（@Scheduled 低峰执行，以 DB 为准）**

| 对账项 | 基准 | 动作 |
|---|---|---|
| 签到位图 | `sign_record` | 当日有记录但位=0 → 补位；位图 key 缺失 → 整月回放重建 |
| 经验镜像 | `user_level.exp` | `ct:exp:cnt` 不一致 → 以 DB 覆盖并同步 ZSet |
| 连续天数 | `user_level.continuous_sign_days` | 抽样比对 streak key，漂移则校正 |

### 6.3 签到流程（Redis 主路径 + DB 同步落库）

```
POST /app/level/checkin
        │
        ▼
0. 组装 KEYS/ARGV（本月位图、昨日位图、streak key、TTL）
   Redis 不可用（开关关闭 / 连接异常）？
   ├─ 是 → 纯 DB 路径：INSERT sign_record（uk_user_date 判重，DuplicateKey 即已签）→
   │        由 last_sign_date 推导连续天数 → 执行与下述第 3 步相同的 DB 事务
   └─ 否 ↓
1. EVAL 签到 Lua → 返回 -1 表示今日已签（幂等返回，不报错）；n = 本次连续天数
        ▼
2. bonus = (n % 7 == 0)
        ▼
3. DB 事务（真相落库）：
   INSERT sign_record(user_id, 今日, continuous_days=n, bonus)   ← uk_user_date 兜底并发穿透
   UPDATE user_level SET exp = exp + 10 + bonus*50,
                          continuous_sign_days = n, last_sign_date = 今天,
                          total_sign_days = total + 1, level = <重算>
   INSERT exp_log (CHECKIN, +10, bizId=yyyyMMdd)
   若 bonus 再 INSERT exp_log (CHECKIN_BONUS, +50, bizId=yyyyMMdd)
   └─ DuplicateKey（并发穿透位图判重的极小概率场景）→ 回滚事务、
      回退该位图位（SETBIT 0）并按「已签」返回
        ▼
4. DEL ct:level:info:{userId}；INCRBY ct:exp:cnt:{userId} 增量；
   可选 ZADD ct:rank:exp {exp} {userId}
        ▼
5. 返回 { gainedExp: 10 + bonus*50, continuousDays: n, levelUp }
```

要点：

- Redis 判重在前、DB 唯一键兜底在后，两层防护；`exp = exp + ?` 为数据库原子自增，不存在丢失更新。
- DB 落库在请求内同步完成（每用户每日 1 次写，量小），经验资产不做异步化；Redis 写（第 4 步）失败仅告警，由对账兜底。

### 6.4 经验发放流程（评论 / 帖子）

```
评论/发帖事务提交（AFTER_COMMIT 事件）
        │
        ▼
1. INCR ct:exp:day:{userId}:{action}:{yyyyMMdd}
   （首次创建时 EXPIRE 至次日 0 点）→ 返回当日已计次数 c
        ▼
2. c > 每日上限次数 → 返回 0，结束（业务无感；计数器无需回退）
        ▼
3. UPDATE user_level SET exp = exp + ?, level = <重算>
   INSERT exp_log（唯一键 uk_idempotent 防重）
        ▼
4. DEL ct:level:info:{userId}；INCRBY ct:exp:cnt:{userId}；
   可选 ZADD ct:rank:exp
        ▼
5. 若等级变化 → 返回/记录升级事件（前端弹升级动效，可选写 notice）
```

- Redis 异常时步骤 1 降级为 `SELECT COALESCE(SUM(exp),0) FROM exp_log WHERE user_id=? AND action=? AND create_time >= 今日0点` 判额（语义等价）；步骤 3 的 DB 写为主，Redis 写失败仅告警不回滚（对账兜底）。
- 等级重算用 `LevelConst.levelOf(exp)`，因为经验只增，无需考虑降级。

### 6.5 配置化与可选扩展

- 上限、经验值收敛在 `ExpAction` 枚举（或 `application.yml` 配置类），调整不改业务代码；评论若最终确认「15 条」口径，仅需把 `COMMENT` 上限 5 → 15。
- **经验排行榜（可选，Key 已预留）**：`GET /app/level/rank?top=10` → `ZREVRANGE ct:rank:exp 0 top-1 WITHSCORES` + 批量补昵称/头衔；个人排名 `ZREVRANK`。是否纳入首期见待确认事项 8。

---

## 七、接口设计

> 遵循项目统一的返回封装（`common` 包 Result）；登录态复用现有鉴权，`userId` 取自当前登录用户，禁止由前端传入。

### 7.1 查询我的等级

`GET /app/level/me`

```json
{
  "code": 0,
  "data": {
    "level": 3,
    "title": "逐曜",
    "exp": 950,
    "currentLevelMinExp": 800,
    "nextLevelExp": 2200,
    "levelProgress": 10.7,
    "isMax": false,
    "totalSignDays": 66,
    "continuousSignDays": 3
  }
}
```

- `levelProgress` = `(exp - 当前级阈值) / (下一级阈值 - 当前级阈值) * 100`，满级时为 100。

### 7.2 每日签到

`POST /app/level/checkin`

```json
{
  "code": 0,
  "data": {
    "gainedExp": 60,
    "detail": [ { "action": "CHECKIN", "exp": 10 }, { "action": "CHECKIN_BONUS", "exp": 50 } ],
    "continuousSignDays": 7,
    "alreadySignedToday": false,
    "levelUp": { "from": 2, "to": 3, "fromTitle": "循迹", "toTitle": "逐曜" }
  }
}
```

- `levelUp` 为 `null` 表示未升级；重复签到返回 200 + `alreadySignedToday: true`（幂等，不报错）。

### 7.3 签到状态

`GET /app/level/checkin/status`

```json
{
  "code": 0,
  "data": {
    "signedToday": true,
    "continuousSignDays": 7,
    "totalSignDays": 66,
    "daysToBonus": 7,
    "recentRecords": [
      { "date": "2026-09-02", "signed": true },
      { "date": "2026-09-03", "signed": true }
    ]
  }
}
```

- `daysToBonus`：距下一次连签奖励还需几天（今日已触发则为 7）；供前端「再签 N 天得 50 经验」提示。
- `recentRecords`：近 7/14 天签到情况，供签到日历渲染；Redis 路径用 `BITFIELD` 一次取出位段，DB 路径查 `sign_record`。

### 7.4 经验流水

`GET /app/level/exp/log?pageNum=1&pageSize=10`

```json
{
  "code": 0,
  "data": {
    "total": 128,
    "list": [
      { "action": "CHECKIN_BONUS", "exp": 50, "remark": "连续签到7天奖励", "createTime": "2026-09-08 09:12:00" },
      { "action": "COMMENT", "exp": 3, "remark": "发表评论", "createTime": "2026-09-08 08:30:00" }
    ]
  }
}
```

---

## 八、前端设计（uni-app）

### 8.1 页面与改动点

| 位置 | 内容 |
|---|---|
| 新增页面 `pages/level/index` | 等级总览：当前头衔徽章、经验进度条、升级阶梯（7 级时间轴）、签到日历、经验流水列表 |
| 个人主页 `AppUserInfoVO` 关联展示 | 头像旁的头衔徽章 + 等级（后端在用户信息/帖子作者 VO 中附带 `level`、`title` 字段） |
| 签到入口 | 「我的」页或等级页顶部，未签到显示按钮，已签到置灰显示「再签 N 天得 50 经验」 |
| 帖子/评论区 | 升级后下次进入展示升级祝贺弹层（一次性，可用本地 storage 记录已展示） |

### 8.2 头衔徽章配色建议（星铁风）

| 等级 | 头衔 | 主题色 | 徽章质感 |
|:---:|:---:|:---|:---|
| 1 | 初旅 | `#9AD0D5` 青灰 | 朴素边框 |
| 2 | 循迹 | `#7BC96F` 草绿 | 朴素边框 |
| 3 | 逐曜 | `#5B8DEF` 星轨蓝 | 细描边 |
| 4 | 织星 | `#B57EDC` 星紫 | 细描边 |
| 5 | 执命 | `#E8B33D` 鎏金 | 金属渐变 |
| 6 | 耀令 | `#F0803C` 曜焰橙 | 金属渐变 + 微光 |
| 7 | 归穹 | `#E6D5F7 → #7FD3E0` 渐变 | 流光动效 |

配色、切图最终以 UI 设计稿为准；建议头衔徽章做成组件 `LevelBadge`，入参 `level`，样式由前端静态映射。

### 8.3 组合式函数

```ts
// composables/useLevel.ts
// 封装 GET /app/level/me、POST /app/level/checkin、GET /app/level/checkin/status
// 暴露：levelInfo, signStatus, doCheckin(), refresh()
```

进度条展示直接使用接口下发的 `currentLevelMinExp / nextLevelExp / levelProgress`，**前端不硬编码阈值**，避免与后端配置漂移。

---

## 九、管理后台（可选）

`ciyuantown-admin` 增加只读查询即可满足首期需要：

- 用户等级列表：按等级/经验排序、搜索用户（复用 `/level` 管理端接口）。
- 经验流水查询：按用户/动作/时间段筛选，用于客服排查「为什么没加经验」。
- 本期不提供经验手动增减；如需运营补发，后续再加管理端 `POST /level/grant`（带操作日志）。

---

## 十、测试用例

| 编号 | 场景 | 预期 |
|:---:|:---|:---|
| T01 | 新用户首次签到 | +10，Lv.1，连续天数 1 |
| T02 | 同日重复签到 | 幂等返回已签到，经验不变 |
| T03 | 连续第 7 天签到 | +60（10+50），`CHECKIN_BONUS` 流水 1 条 |
| T04 | 连续第 8 天签到 | 仅 +10 |
| T05 | 连续第 14 天签到 | 再次 +50（滑动周期第二次奖励） |
| T06 | 中断 1 天后签到 | 连续天数重置为 1，无奖励 |
| T07 | 当日第 5 条评论 | +3（累计评论经验 15） |
| T08 | 当日第 6 条评论 | 评论成功，经验 +0 |
| T09 | 发布帖子 | +15，流水 `biz_id` = 帖子 ID |
| T10 | 同一帖子重复触发发放事件（重试） | 唯一键拦截，经验只加一次 |
| T11 | 经验 199 时获得 1 点 | 升至 Lv.2 循迹，返回 `levelUp` |
| T12 | 经验 17999 → 18000 | 升至 Lv.7 归穹 |
| T13 | 经验超过 18000（如 20000） | 仍为 Lv.7，`isMax: true`，进度 100% |
| T14 | 并发同时签到（同用户两请求） | 仅一条 `sign_record`，经验只加一次 |
| T15 | Redis 计数器被清空 | 回源 `exp_log` 正确统计，不超发不漏发 |
| T16 | 跨天边界 23:59:59 / 00:00:01 签到 | 分别记入两天，连续天数 +1 而非中断 |
| T17 | 存量用户首次访问等级接口 | 自动初始化 Lv.1 / 0 经验，不报错 |
| T18 | `ciyuantown.redis.enabled=false` 时签到 | 走纯 DB 路径，结果与 Redis 路径语义一致 |
| T19 | 签到过程中 Redis 抛异常 | 自动降级 DB 路径，签到成功且不返回 5xx |
| T20 | 跨月连续签到（8 月 31 日 → 9 月 1 日） | 连续天数不中断（昨日位图取上月） |
| T21 | streak key 丢失但昨日已签 | 回源 DB 修复连续天数，不误重置为 1 |
| T22 | 当月位图 key 丢失 | 由 `sign_record` 回放重建，日历与连续天数正确 |
| T23 | 每日额度 key 过期后次日再评论 | 额度重置，重新开始计经验 |

---

## 十一、开发任务拆分

| 阶段 | 任务 | 产出 |
|:---:|:---|:---|
| P0 | 确认待确认事项（见第十二节） | 冻结规则口径 |
| P1 | 建表 + 实体/Mapper + `LevelConst` | 数据层就绪 |
| P2 | `ExpService`（幂等发放 + Redis 每日额度 + 等级重算 + DB 降级路径） | 核心能力 |
| P3 | `SignService`（Lua 位图签到 / 状态查询 / DB 降级）+ `AppLevelController` 四个接口 | 后端完成 |
| P4 | post 模块评论/发帖接入（AFTER_COMMIT 事件） | 全链路打通 |
| P5 | uni-app：`useLevel` + 等级页 + 签到入口 + `LevelBadge` 组件 | 前端完成 |
| P6 | 用户信息/帖子作者 VO 附带等级头衔 | 展示打通 |
| P7 | 测试用例回归 + 存量用户初始化脚本 + Redis↔DB 对账定时任务 | 上线 |
| P8（可选） | 管理端查询页、升级 notice 推送 | 增强 |

---

## 十二、待确认事项

以下口径影响实现细节，**开发前需拍板**（文档当前按「默认口径」编写，确认后更新对应小节）：

| # | 问题 | 默认口径（本文档采用） | 备选 |
|:---:|:---|:---|:---|
| 1 | 评论「每日上限 15」指经验值还是条数？ | **经验值 15 点**（即 5 条评论满额） | 15 条评论（45 经验/日） |
| 2 | 连签奖励「每周一次」按滑动 7 天还是自然周？ | **滑动周期**：第 7、14、21… 天发放 | 自然周（周一~周日）内最多 1 次 |
| 3 | 断签处理 | **连续天数清零**重新计数 | 可考虑补签卡（本期不做） |
| 4 | 发帖经验发放时机与防刷 | 发布成功即发（暂无上限）；**建议**审核通过才发放或加每日上限 | 维持现状（有刷分风险） |
| 5 | 评论超上限时是否写 `exp=0` 流水 | **不写**，流水只记录实际发放 | 写 0 值流水便于用户理解 |
| 6 | 经验是否可扣减（删帖/删评论/封号） | **本期不扣减**，经验只增不减 | 后续版本再做 |
| 7 | 升级是否需要通知 | 仅接口返回 `levelUp` + 前端弹层 | 接入 notice 模块推送 |
| 8 | 是否需要经验排行榜（Redis ZSet 已预留） | 本期不做 | 新增 `GET /app/level/rank` |

---

## 附录一：数值校验

等级表内部一致性验证（本级所需 = 本级累计 − 上一级累计）：

```
Lv.2: 200  − 0     = 200   ✓
Lv.3: 800  − 200   = 600   ✓
Lv.4: 2200 − 800   = 1400  ✓
Lv.5: 5000 − 2200  = 2800  ✓
Lv.6: 10000 − 5000 = 5000  ✓
Lv.7: 18000 − 10000 = 8000 ✓
```

满级预估（仅参考）：只签到（10/天，含连签奖励约 60/7 天≈8.57/天）约需 **5.8 年**；签到+满额评论+1 帖（40/天，连签日 90/天）约需 **1.5~2 年**，节奏偏长线运营；如期望更活跃的升级反馈，可整体缩放阈值（如 ÷2），阈值结构不变。

---

## 附录二：Redis 命令速查（开发时对照）

| 场景 | 命令示例 |
|---|---|
| 签到（判重+记位+连续天数） | `EVAL <§6.2 Lua> 3 ct:sign:bits:{u}:{yyyyMM} ct:sign:bits:{u}:{昨日MM} ct:sign:streak:{u} {今日偏移} {昨日偏移或0} {TTL秒}` |
| 查某天是否签到 | `GETBIT ct:sign:bits:{userId}:{yyyyMM} {day}` |
| 本月签到天数 | `BITCOUNT ct:sign:bits:{userId}:{yyyyMM}` |
| 近 N 天日历位段 | `BITFIELD ct:sign:bits:{userId}:{yyyyMM} GET u{N} {起始偏移}` |
| 每日经验额度 | `INCR ct:exp:day:{userId}:{action}:{yyyyMMdd}`（首建时 `EXPIRE` 至次日 0 点） |
| 等级缓存 | `GET ct:level:info:{userId}`（miss → 查 DB → `SET EX 300`），经验变动后 `DEL` |
| 经验镜像 | `INCRBY ct:exp:cnt:{userId} {n}`（miss → 回源 `user_level.exp`） |
| 排行榜（可选） | `ZADD ct:rank:exp {exp} {userId}` / `ZREVRANGE ct:rank:exp 0 9 WITHSCORES` / `ZREVRANK` |
| 位图重建 | 按 `sign_record` 逐日 `SETBIT ct:sign:bits:{userId}:{yyyyMM} {day} 1` 回放 |
