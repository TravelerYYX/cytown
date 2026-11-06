# 配置入口的跳转目标

“运营配置 → 首页配置”中的“快捷入口管理”和“我的页面入口管理”均支持：

- **跳转链接**：HTTP/HTTPS 完整地址，支持软件内网页或外部浏览器，并可控制内置网页顶部栏。
- **跳转 APP 页面**：下拉选择次元町页面。帖子详情、通知详情和通知列表需要在生成的页面地址中补全对应 ID；可保留其他页面参数。

首页、关注、消息、我的会切换自定义底部导航对应栏目。发布封面、选话题和草稿选择等依赖上一步操作的辅助页面未放入入口选项。

数据库新增 `home_entry.target_type` 和 `mine_entry.target_type`，取值 `link` / `app`；原有入口默认 `link`。`link_url` 根据跳转类型存放网页链接或 APP 页面地址。打开方式字段继续使用 `inapp` / `browser`，APP 页面保存时统一为 `inapp`。

本地数据库已执行迁移。其他数据库需执行 `ciyuantoen-back/ciyuantown-back/doc/sql/p21-entry-target.sql`（可重复执行），再重启后端并更新 APP。页面目录由后端 `EntryTarget.PAGES` 统一提供给两处后台表单，保存时也会验证路径及所需参数。

验证记录：

- `EntryTargetIntegrationTests` 与 `MineEntryIntegrationTests` 共 7 项通过，使用真实本地 MySQL；测试数据写入均回滚。
- 后台 `pnpm typecheck`、`pnpm build` 通过。
- H5 开发模式和 Android 资源编译通过；尚未进行 APK 打包或真机验收。
- 在 `次元町` 执行 `npm run check:entry-targets`：使用浏览器模拟接口验证后台保存/回显/参数校验、页面目录失败重试、APP 设置页和主栏目跳转、帖子参数及旧网页入口。
- 浏览器截图和结果位于 `次元町/artifacts/entry-targets/`。

