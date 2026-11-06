# 头像跳转与他人主页

业务头像统一通过 `openUserProfile(userId)` 导航。首页单列及瀑布流、动态、帖子详情、图片浏览器、评论及回复、粉丝关注列表和私聊均已接入。用户 ID 按字符串传递。头像组件默认不导航，资料编辑、头像选择、头像框预览及“我的”原有编辑入口保持用途。

自己的头像切换到已有主页面的“我的”，没有主页面时重新进入主页面。他人的头像打开 `/pages/user-profile/index?userId=…`。同一主页不递归打开，连续点击共用导航锁。信息流头像的外层按钮也消费头像事件，避免原来的帖子跳转抢先执行。

主页使用参考图的蓝色背景、头像资料区域、统计栏、白色圆角分栏及底部聊天和关注按钮。字体使用项目默认字体；卡片沿用现有次元町卡片及用户自己的背景。评论、合集和收藏显示现有 `EmptyState`；搜索、更多和他人粉丝关注名单本期不扩展后台能力。

## 接口

- `GET /app/user/{userId}/profile`：公开资料、头像框、性别、签名、卡片背景、等级称号、余额、省份、统计、查看者与作者的关注关系。独立返回类型不包含 QQ、邮箱、登录账号或会话信息。
- `GET /app/user/{userId}/posts?pageNum=1&pageSize=10`：按发布时间及 ID 倒序，只返回该作者已发布帖子。客户端分页去重，复用帖子交互状态管理；不会把查看者自己的收藏或发布列表用作他人的内容。
- `POST /app/chat/conversations`，请求 `{ "peerId": "用户ID" }`：幂等打开会话，返回现有 `ChatConversation` 格式。
- `GET /app/chat/conversations/{id}`：仅会话参与者可读取，用于页面刷新或尚未发消息的空会话。空会话不因打开而自动关注、发送消息或出现在对方消息列表；发送首条消息后显示。既有关注生成空会话的行为保留。
- `GET /app/user/location`、`POST /app/user/location/refresh`：登录态读取或刷新自己的省份；客户端不传目标用户或原始 IP。

## 属地与部署

先执行 `ciyuantoen-back/ciyuantown-back/doc/sql/p30-user-profile-location.sql`，再启动更新后的后端，最后更新客户端。迁移为 `app_user` 添加可空的 `ip_province`、`ip_located_at`，可重复执行，不回填推测位置。

后端配置 `IP_LOCATE_KEY`；地址默认 `https://ip.moonya.cn/api/locate`，可用 `IP_LOCATE_URL` 覆盖。现有本地定位密钥已接入后端 `.env`，没有写入源码、构建产物或验收报告。

`IP_LOCATE_TRUSTED_PROXIES` 默认 `127.0.0.1,::1`。生产使用反向代理时配置确切可信代理地址，代理应覆盖来源头。服务从可信端解析地址，明确传入客户端 IP 查询，不使用服务器出口替代客户端。USB 隧道、回环和内网地址无法得到真实公网省份时保留已知位置，否则显示“未知”。

登录和返回前台触发属地刷新；成功记录保留 24 小时。外部查询限时，失败保留上次成功结果；账号切换后不会把上一账号的请求结果显示到新账号。

本地启用状态：2026-11-01 已使用用户提供的管理员连接完成属地字段迁移，重复执行验证通过。当前开发后端已加载更新后的接口，真实用户主页和发布列表返回成功，无需额外重启。执行记录见 `artifacts/user-profile-20261101/migration.json`。

## 验收

- Android：`node scripts/verify-user-profile-android.cjs`。使用已连接的 uni-app x Vapor bytecode 原生调试基座、真实触摸、原生节点几何和 ADB 截图；不使用 WebView 模拟 Android。测试数据为本地隔离 HTTP fixture，结束恢复原账号与 USB 转发。
- H5：`node scripts/verify-user-profile-h5.cjs`。默认开发地址 `http://127.0.0.1:15275`，可用 `PROFILE_H5_ORIGIN` 指定。覆盖 320/390/430px、关注、私聊、全部头像入口、分页、错误重试、空态、注销用户及超过 JavaScript 安全整数范围的字符串 ID。
- 导航检查：`node scripts/check-user-profile-navigation.cjs`。
- 后端隔离集成：运行已有 `scripts/verify-user-accounts.py --tests UserProfileIntegrationTests,SocialInteractionTests`，使用其临时 MySQL 实例选项。涵盖公开投影、作者隔离、状态过滤、分页、关注状态、属地归属、会话幂等、陌生人发送和参与者权限。
- 后端服务测试：`ChatTests,ChatAdministrationTests,ChatReplyTests,UserLocationServiceTests`，涵盖陌生人聊天、禁言封禁、撤回与引用回归、属地节流、失败保留及代理来源验证。

结果、原生截图与源码备份位于 `artifacts/user-profile-20261031/`。两端正式资源构建均通过零诊断门禁，构建使用相同源码指纹。原生核心与滚动检查通过。

两个旧检查存在与此次变更无关的失败：架构检查的旧素材基线不接受既有 `static/icons/top-cyan.svg`；运行边界检查的 VM 未给已有 `file-storage.uts` 的 `image-loading` 导入提供 `require`。未改动该素材或这两个检查器以掩盖结果。
