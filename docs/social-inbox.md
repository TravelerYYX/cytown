# 互动通知与帖子手机预览

客户端新增评论和@、新增关注、收到的赞。回复、转发@、主动@分别分页；点赞包含帖子和评论，关注记录初始7条。标题引用采用标题、正文、发送时间的顺序。站内转发复用帖子编辑器、草稿和审核，审核通过后才通知@用户并增加原帖转发数。

## 数据库与接口

更新后端前，备份目标数据库并执行 `ciyuantoen-back/ciyuantown-back/doc/sql/p31-social-inbox.sql`。脚本增加 `post.repost_of_post_id`、`social_notification` 和 `social_publication`，回填数据库中仍存在的历史回复、关注、帖子/评论点赞及帖子@，历史通知默认已读。脚本可重复运行，首次写入的截止时间防止后续正常通知被回填误标已读。取消或删除后已经没有记录的互动无法恢复。

- `GET /app/social/summary`：当前用户的分类未读数。
- `GET /app/social/notifications`：`kind=reply|repost|mention|like|follow`，支持 `following`、`pageNum`、`pageSize`。
- `POST /app/social/read`：仅标记当前接收人的指定字符串ID列表。
- `GET /app/post/{postId}/comments/{commentId}/locate`：返回目标根评论与回复所在分页，沿用评论排序和筛选。
- 管理员 `GET /post/{postId}/comments` 和 `GET /post/{postId}/comments/{rootId}/replies`：允许查看待审核及已下架帖子，普通用户接口继续检查发布状态。
- 发布参数增加可选 `repostOfPostId`，帖子列表/详情增加原帖摘要，旧发布请求继续兼容。

通知ID、用户ID、帖子ID均以字符串传到客户端。业务变更与通知写入同一事务；投递及审核发布通过唯一业务标识去重，不通知本人。

## 手机预览构建

后台“查看帖子”在右侧390px视口中复用H5帖子详情。预览只接受同源父窗口的数据响应；管理员凭据留在父窗口。正文、互动数和已加载评论每3秒同步；隐藏、关闭、删除后停止请求，保留滚动位置、展开的回复和媒体组件。

后台 `pnpm build` 会先构建H5，再将资源打包到后台的 `/post-preview/`。正式构建使用前端的生产API配置；本地验证使用已有开发API配置：

```powershell
# 在 ciyuantoen-back/ciyuantown-admin 中运行
$env:CT_BUILD_MODE='development'
pnpm typecheck
pnpm build
```

开发后台默认提供已构建的H5资源。需要H5热更新时，先在前端设置 `CT_POST_PREVIEW_BUILD=1` 后运行 `npm run dev:h5 -- --port 15273`，再在后台设置 `CT_POST_PREVIEW_DEV_ORIGIN=http://127.0.0.1:15273`，运行 `pnpm dev`，后台将同源 `/post-preview/` 代理到H5服务。

新增SVG位于前端 `static/icons/social/`，原生PNG由 `node scripts/generate-social-assets.cjs` 从同一份SVG生成。

## 行为与验证

- 通知定位先显示帖子顶部，再滚动至评论；跨分页与嵌套回复均可定位。浅黄色背景保持2秒，再渐隐600毫秒；用户触摸或滚动后停止位置校正。
- 每次主动点击“私信感谢”进入聊天并发送“谢谢你点赞了我的笔记”。跳转标识仅驻留内存且使用一次，刷新/返回不自动重发；失败通过现有消息重试入口沿用原clientId。
- `node scripts/verify-social-inbox.cjs`：隔离接口验证320/390/430px截图、页签、分页定位、关注展开、感谢发送/重试和转发@。
- `node scripts/verify-post-phone-preview.cjs`：验证后台详情及预览刷新、只读桥接、滚动/回复展开保持、断网重试、删除停止同步。
- `node scripts/verify-social-inbox-android.cjs`：使用已连接的HBuilderX原生调试基座和隔离接口，结束后恢复登录存储及USB转发。需先将最新前端运行到Android并提供9229调试端口。
- 后端使用 `scripts/verify-user-accounts.py --tests SocialInboxIntegrationTests,CommentIntegrationTests,SocialInteractionTests,PostIntegrationTests,ChannelPublishAccessTests`，配合 `--mysqld` 在独立MySQL实例中验证，不修改应用数据库。

截图和测试报告位于 `artifacts/social-inbox-20261102/`。部署时须先执行P31，再更新后端和客户端；本次验证不执行实际应用数据库迁移或发布到外部环境。

当前原生验证环境为MuMu模拟器。已取得入口、评论列表及嵌套回复浅黄色定位背景截图；完整原生脚本尚未通过，后续调试基座启动操作被自动审批拒绝。未连接真实Android手机，因此这些截图不代表真机验收。H5交互及后台预览自动化验收独立通过，具体结果见各自的JSON报告。
