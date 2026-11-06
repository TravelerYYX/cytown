# 每日签到

2026-10-23：APP、后台、服务端及本机数据库已接入。后台入口为「运营管理 → 签到管理」，帖子管理中的「设为推荐」独立于「首页资讯轮播」。

## 使用

- 首页第一项「每日签到」使用现有 APP 页面跳转配置，地址 `/pages/checkin/index`；首页仍为 APP 默认启动页。
- 按北京时间实际日期领取。默认每次领取 10 次元币、10 经验；每月复用31天模板，自动处理大小月和闰年。
- 点当天橙色奖励卡片后领取；点击其他日期可看组合详情。默认三列六格，展开可看整月。
- 后台可逐日配置奖励、卡片主图标、头像框永久或有效天数。次元币、经验共用图标及头像框可上传替换；均复用现有图片上传与压缩服务，自动转 WebP，并保存及显示处理前后字节大小。
- 补签只限当月过去未签日期，默认10币/次，先检查已有余额再扣费和发奖励；用户确认后若配置版本变化，服务端拒绝扣费并要求刷新。补签不修复实际连签。
- 等级页与签到页共用签到事务。日历经验替代旧每日经验配置；连续实际签到每满7天的额外经验仍在等级体系配置。
- 现有免费头像框保留。签到框必须领取后佩戴，限时从实际领取起算，重复续期；永久权益不降级，重复永久奖励无补偿。公共头像组件收到到期时间后自动隐藏，保存接口也验证权益。
- 推荐池仅包含后台标记且已发布的帖子：最新1篇＋其余随机2篇，不足按实际展示。查看更多选择启用且 `showAllPosts=true` 的频道，不依赖频道名称或ID。
- 提醒、分享、账号切换的 UI 保留，点击提示暂未开放；记录、规则已实现。

## 接口与一致性

| 接口 | 用途 |
| --- | --- |
| `GET/POST /checkin/config` | 后台配置，遵守现有管理员鉴权和只读账号写入限制 |
| `GET /app/checkin/config` | 公共31天配置 |
| `GET /app/checkin/me` | 当前账户的当月状态和次元币余额，需登录 |
| `POST /app/checkin/sign` | 当天领取，需登录 |
| `POST /app/checkin/makeup` | `{date, version}`，需登录；按确认时的配置版本补签 |
| `GET /app/checkin/history` | `pageNum/pageSize` 分页签到记录，需登录 |
| `GET /app/checkin/frames` | 奖励框目录 |
| `GET /app/checkin/frames/owned` | 个人框权益及到期时间，需登录 |
| `GET /app/checkin/recommendations` | 最新＋随机推荐 |
| `POST /post/recommendation` | 后台独立推荐开关：`{id, recommended}` |

`/app/level/sign-in` 保留原响应结构，并委托统一签到服务。`/image/upload` 保留 `url`，新增 `originalBytes/processedBytes`；原图片处理入口继续兼容。

用户行锁串行化同一用户的新旧签到和补签，日期主键与流水事件键防重。扣币、币奖励、经验、等级、框权益、日期记录在一个事务中完成。领取记录保存奖励快照及配置版本；已领取奖励不因后台调整重算。补签不受互动行为的当日经验限额影响。

数据库扩展 `app_user` 余额及当前框到期时间、`user_level_signin` 领取快照、`post` 推荐标记；新增 `checkin_config`、`user_coin_ledger`、`user_avatar_frame`。头像框目录随签到配置保存，已有框不可删除，以免已领取权益丢失。

## 迁移与运行

迁移脚本：`ciyuantoen-back/ciyuantown-back/doc/sql/p23-checkin.sql`，可重复执行，保留后续后台配置和入口修改。后台路由权限沿用项目静态路由体系，不新增另一套菜单表。

已备份并更新本机 `127.0.0.1:3306/ciyuantown`。备份与核对记录在 `ciyuantoen-back/ciyuantown-back/backups/checkin-20261023-173717/`。仅删除迁移前明确核对到的2条旧签到记录，保留用户经验、等级及所有历史经验流水；历史清理未嵌入可重复迁移脚本。

本机后端已使用现有 `scripts/dev.ps1` 在 PowerShell 7 下重新启动，实际接口验证见 `artifacts/checkin/live-verification.json`。数据库凭据不写入源码、文档或核对报告。

## 验证

- 独立临时 MySQL：60项通过，含 `CheckinApiTests`、`CheckinIntegrationTests`、`CheckinConcurrencyTests`、`LevelIntegrationTests`、`ImageProcessServiceTests`、`EntryTargetIntegrationTests`、`UserDeletionTests`。临时数据库与服务均已清理。
- 覆盖月份28/29/30/31天、北京时间零点、跨月、旧流水兼容、奖励快照、配置冲突、余额不足、并发签到/补签、账务异常回滚、实际连签、限时框续期/到期/永久权益、推荐池和鉴权。
- `npm run check:checkin`：首页启动、三列320/390/430宽度、月份、单次领取、选日补签、记录、规则、全部频道跳转、到期隐藏、未领取框锁定；后台31天编辑、上传WebP及压缩大小保存回显。
- `verify-entry-targets.cjs`：两组入口配置、页面目录重试、必填ID、实际 APP 页面/主栏目跳转和旧网页入口通过。
- 管理端 `pnpm typecheck`、`pnpm build`，后端 Java21 编译打包通过。H5、Android资源编译报告位于 `deliverables/uniapp-x-migration/builds/`。未进行APK打包或真机运行验收。运行报告另存于 `artifacts/checkin/client-builds.json`。
- 原生运行边界检查通过。额外的全仓历史资源基线检查仍报告既有 `static/icons/top-cyan.svg` 与2026-10-17基线不一致；该图标未由本次签到功能修改。

前端截图和交互结果在 `次元町/artifacts/checkin/`；后端测试统计在 `artifacts/checkin/backend-tests.json`。

## 图片与字体

星汐Q版角色、次元币、经验晶星和星愿头像框使用内置 `image_gen` 生成。完整提示词见 `artifacts/checkin/asset-prompts.json`，原始PNG在同目录 `source-images/`；项目使用的压缩资源在 `次元町/static/images/checkin/` 和服务端 `src/main/resources/static/uploads/checkin/`。

字体采用 [霞鹜文楷轻便版](https://github.com/lxgw/LxgwWenKai-Lite)，按常用汉字及本页字符制作子集并将内部家族名改为 `Ciyuantown Checkin`。字体和完整OFL许可在 `次元町/static/fonts/`；由此避免依赖各设备预装字体。
