# 我的页面入口配置

管理位置：后台「首页配置」中的「我的页面入口管理」。登录前后共用配置，可新增、编辑、删除、启停及拖动排序。标题、介绍、SVG 图标和 HTTP/HTTPS 链接必填；图标可上传文件或粘贴代码。每项可选择软件内打开（顶部栏可开关）或浏览器打开。

无启用入口时整组入口及其留白隐藏，帖子区域向上接续，白色面板保留顶部圆角。超过一屏可横向滑动。进入“我的”或应用回到前台会刷新配置，首次加载失败不显示入口，后续失败保留最近成功的配置。上滑时顶部栏逐渐显现，吸顶频道区域保持圆角并平滑切换；发布、评论、合集、收藏的内容按切换方向过渡，系统要求减少动效时关闭动画。

## 数据库与接口

- 增量 SQL：`ciyuantoen-back/ciyuantown-back/doc/sql/p15-mine-entry.sql`，可重复执行，仅新增 `mine_entry` 表，不添加示例数据。本地 `ciyuantown` 库已导入。
- 管理：`GET /mine/entry/list`；`POST /mine/entry/create`、`update`、`delete`、`sort`。删除参数为 `{id}`，排序参数为 `{ids}`（完整列表）。
- 图标：`POST /mine/entry/icon/upload`（multipart 的 `file`）和 `/mine/entry/icon/code`（JSON 的 `svg`），返回 `{url}`。
- 公开：`GET /app/mine/entries`，返回启用项，按 `sortOrder,id` 升序排列。
- 入口字段：`id,title,description,iconUrl,linkUrl,openMode,showTopbar,sortOrder,enabled`。公开项不包含管理状态与时间字段。响应沿用项目的 `code,msg,data` 包装。

SVG 最大256KB，必须为 SVG 命名空间下的纯矢量内容；通过安全 XML 解析和元素/属性白名单拒绝脚本、事件、外部资源、DTD 与外部实体。两种输入均保存成独立 SVG 资源，使用现有媒体存储服务，返回正确的 `image/svg+xml` 类型。保存入口时校验图标确实来自此上传接口。

## 验证

- 后端：`SvgIconServiceTests`、`MineEntryIntegrationTests`、`MineSvgStorageIntegrationTests`，覆盖权限、CRUD、排序、校验、真实本地 SVG 存储和公开读取。数据库测试事务回滚；真实文件测试使用临时目录并随事务回滚清理，不改动当前媒体存储配置。
- 前端：`npm run typecheck`；后台：`pnpm typecheck` 和 `pnpm build`。
- 页面：前端目录执行 `npm run check:mine-entries`。默认应用地址 `http://127.0.0.1:15273`，后台地址 `http://127.0.0.1:9527`；可用 `UI_TEST_URL` 和 `ADMIN_UI_TEST_URL` 覆盖。
- 页面检查使用接口夹具，不向业务库写入测试内容；覆盖320/390/430宽度、登录前后、0/1/7项、横向滚动、吸顶位置、刷新失败、链接参数以及后台两种 SVG 输入和保存失败保留表单。
- 当前 `.env.production` 未配置 API 地址。H5 与 App 验证使用 `npm run build:h5 -- --mode development` 和 `npm run build:app -- --mode development`，正式发布前需配置实际生产 HTTPS API 地址。
