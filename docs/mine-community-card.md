# 个人卡片真实社区数据

卡片“天空岛”后的等级及“当前称号”分别使用 `/app/level/me` 的 `level`（1–60）与 `title`。活跃天数使用 `/app/user/statistics` 的 `activeDays`。第三栏“次元币”使用 `/app/user/me` 的 `coinBalance`，完整显示当前账户余额，返回“我的”界面时刷新，并同步签到、补签后更新的账户缓存。幻想剧诗及卡片样式保持原样。加载失败时保留已获取的数据；尚无数据时显示 `—`，不使用示例数值。

活跃按 Asia/Shanghai 自然日累计，同一天重复登录、打开或多设备请求仅计一次，不要求连续。验证码登录、密码登录、官方账号登录及有效登录状态下的接口请求都会记录活跃；App 回到前台会请求 `/app/user/me`，因此跨天打开时无需重新登录。未登录、失效或被禁用的会话不计入。

部署顺序：先在业务数据库执行 `ciyuantoen-back/ciyuantown-back/doc/sql/p19-user-activity.sql`，再更新后端和客户端。迁移为 `app_user` 增加 `active_days` 与 `last_active_date`，可重复执行。旧数据只能恢复已知的最近一次登录日期（计一天），不会根据注册时间或示例值推算历史活跃。

验证：`scripts/verify-user-accounts.py` 会在隔离数据库中验证迁移，并可通过 `--tests UserActivityTests,AccountSessionTests,UserAccountManagementTests,LevelIntegrationTests` 检查登录、并发计数及相关回归；卡片浏览器检查和截图见 `artifacts/mine-community-card/`。
