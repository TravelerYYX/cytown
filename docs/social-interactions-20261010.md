# 点赞、收藏、关注交付记录

已接通帖子列表、详情、图片查看器、收藏频道、个人统计及粉丝/关注名单。所有入口共用服务端确认后的互动状态；只有当前操作入口触发动画。

## 接口

- 原有 `POST /app/interaction/toggleLike`、`toggleFavorite`、`toggleFollow` 保留；关注结果补充 `followedBy`。
- 新增 `GET /app/interaction/followState?userId=...`，用于超时后只读核对状态。
- 新增登录接口 `GET /app/post/favorites`、`GET /app/user/statistics`、`GET /app/user/followers`、`GET /app/user/following`。列表沿用 `pageNum`、`pageSize` 及现有分页响应。
- 帖子列表与详情增加 `liked`、`favorited`、`following`、`followedBy`、`favoriteCount`。双向关系对应关注、回关、已关注、互相关注。
- 复用现有关系表、事务锁及唯一约束，无新增数据库迁移。数据库为互动状态与动态计数的来源；公共缓存对象复制后再填充个性化字段，批量读取关系，避免逐帖查询。
- 粉丝/关注名单以关系建立时间倒序；收藏以收藏时间倒序，仅展示已发布且仍存在的帖子。获赞沿用已发布帖子的有效点赞总数。

## 素材与播放

- 图片3对应的列表评论/点赞图标已通过 imagegen 生成透明 PNG，保存在前端 `static/images/interactions/`。
- 两个原 GIF 保存在同目录的 `source/`，未改动用户提供的原文件。
- 运行 `python scripts/build-interaction-frames.py` 重建透明首帧、尾帧、PNG 图集、时序清单和 CSS。需要 Pillow。图集先完整写入临时文件再替换；相同像素不重复写入，避免 HBuilder 同步半成品。
- 点赞 26 帧、收藏 33 帧，总时长均为 1440ms。使用原帧时长的离散 CSS 动画；图片加载完成后开始计时，结束切回橙色静态图。
- 服务端确认新增互动后才递增当前入口的播放信号；取消、状态恢复和其他入口的同步均不播放。页面隐藏或组件销毁时停止。
- 动态图与静态图使用不同节点键值，避免 uni-app 复用节点后保留动画样式。

## 验证

- JDK 21 下执行 `SocialInteractionTests`、`P2IntegrationTests`、`PostIntegrationTests`：24 项全部通过。新增测试账号与数据通过事务回滚。
- `npm run typecheck` 通过。
- `npm run check:interactions` 通过。H5 测试使用受控接口响应，覆盖按钮请求、计数绑定、重复点击保护、单次动画、取消、错误保持状态、回关/互关、收藏频道、名单、图片查看器及评论锚点。
- `npm run build:h5 -- --mode development`、`npm run build:app -- --mode development` 通过。沿用现有 `.env.development` 的 API 地址；没有配置或推测生产 API 域名。
- 两种构建命令共用 `scripts/build-with-assets.cjs`，构建成功后补齐 `static/`，确保开发模式构建也包含透明图标和动画图集。
- 已在连接的 Android 设备、本项目 HBuilder 调试基座中运行新构建。`node scripts/verify-android-interactions.cjs` 验证实际接口的点赞/收藏、快速重复输入、单次动画、取消无动画、静态节点与图片解码。测试恢复了操作前的互动状态与计数。
- 白、灰、黑背景的透明素材及实际页面截图、H5/Android 检查结果位于前端 `artifacts/interactions/`。Android 原调试资源备份保存在该目录的 `android-backup/`。

本次未新增私信、互动通知或他人个人主页；Android 验证针对 HBuilder 调试基座，未另行生成签名安装包。
