# 通知接收与 Android 推送

## 已实现的业务规则

- 分类规则 `all` 固定全体、`users` 固定指定用户、`choose` 发送时选择。指定对象为未删除的 APP 用户，支持昵称、账号、UID 搜索，最多选 1000 人。
- 发布生成独立批次及接收范围快照，修改分类不改变历史批次。全体通知对以后注册的用户可见。草稿保存发送设置，再次发布重新应用分类规则。
- 普通修改已发布正文不会重复产生未读或推送。更改已发布通知分类、接收范围，需要先下架，再发布。
- 上线前历史通知默认已读。用户表 `notice_read_since` 以微秒精度保存默认已读起点，之后注册的用户不把注册前的公告算作未读。
- 进入分类列表成功后，提交第一页响应附带的全部可见批次 ID，将进入时的通知设为已读；之后发布的消息不会被清除。直接打开详情仅标记当前批次。
- 游客只看公共通知，不记录个人已读；登录账号的列表、详情、摘要和未读统计都使用接收权限校验，不共用个人缓存。

## 本机迁移与运行

迁移文件：`ciyuantoen-back/ciyuantown-back/doc/sql/p25-notice-delivery.sql`，在旧版本停止写通知后执行一次。旧分类保持全体用户，推送默认关闭。

2026-10-25 已对本机 MySQL 执行迁移；执行前备份位于 `ciyuantoen-back/ciyuantown-back/backups/notice-delivery-20261025-141651/before.sql`，包含原通知、分类和系统配置。新增用户默认已读字段只增加列，不修改原账号资料。数据库密码未写入项目。

回滚脚本：同目录 `p25-notice-delivery-rollback.sql`。先停止新版服务并备份新增数据。脚本会把定向通知下架，防止旧版将定向内容公开，再移除新增表和字段；不会自动重新公开这些通知。需要完整恢复迁移前数据时，使用备份，但会丢弃上线后新增的通知，执行前需另行保留。

## 系统推送开通和配置

1. 在 DCloud 开发者中心为 `__UNI__B1A811F` 开通 UniPush 2，关联 uniCloud 阿里云空间；HBuilderX 中为本项目关联同一空间。核对 Android 包名、签名 SHA1 与实际安装包一致。
2. 在 UniPush 控制台配置所需的华为、荣耀、小米、OPPO、vivo 等厂商离线通道。项目当前配置 `uni-push: {}`，正式打包前通过 HBuilderX 的 Android 可选模块选择实际已开通的厂商 SDK；空配置不代表厂商通道已开通。
3. 上传 `次元町/uniCloud-aliyun/database` 中两个集合 schema。为 `ct-notice-push-nonces.expiresAt` 建立 `expireAfterSeconds=0` 的 TTL 索引；`ct-notice-push-jobs` 保留请求去重记录，勿在消息仍可能重试时删除。
4. 部署云函数 `次元町/uniCloud-aliyun/cloudfunctions/ct-notice-push`，安装其 `uni-cloud-push` 扩展依赖，设置服务端环境变量 `CT_PUSH_APP_ID=__UNI__B1A811F` 和至少 32 位随机密钥 `CT_PUSH_SECRET`，将函数 URL 化为 HTTPS 接口。
5. 后台“运营管理 → 系统推送配置”填入同一应用标识、函数 HTTPS 地址及同一签名密钥，保存并开启总开关。密钥 AES-GCM 加密存库，查询不返回明文；留空保存保留原密钥。后端 `NOTICE_CONFIG_KEY` 默认复用 `MEDIA_CONFIG_KEY`，再回退到现有 `JWT_SECRET`；部署后应保持该加密密钥稳定。
6. 重新打包正式 Android APK 或自定义调试基座。标准基座可以验证站内界面，不能证明应用专属 UniPush 或厂商离线通道可用。APP 设置中“系统推送及提醒设置”支持主动管理通知权限、复制 CID；后台测试只显示服务商已受理，需在手机确认。
7. 发布通知时勾选“同步发送系统通知”。总开关与每条通知开关默认均为关闭；不开启系统推送时站内通知、权限与未读独立可用。

官方配置资料：[开通服务](https://doc.dcloud.net.cn/uni-app-x/uni-push/open.html)、[Android 推送模块](https://doc.dcloud.net.cn/uni-app-x/collocation/manifest-android.html)、[推送服务端参数](https://doc.dcloud.net.cn/uniCloud/uni-cloud-push/api.html)。

## 推送任务与故障处理

- 事务内固定发布批次及当时已登录、已绑定的目标设备；全体用户通知也仅向这些合法绑定设备发送系统通知，不调用不受账号约束的设备广播。
- 每个批次、CID 只有一个任务，事务提交后由 5 秒间隔任务分发；重试等待逐步增加，最多尝试 5 次，再显示失败。后台“重试推送”只重试当前已发布批次的失败任务。
- 状态 `pending` 待发送、`sending` 发送中、`accepted` 服务商已受理、`failed` 失败、`cancelled` 已取消。服务商受理不等于手机送达。
- 云函数校验原始 HTTP 请求体 HMAC-SHA256、时间戳和 nonce；数据库唯一请求 ID 防止多个实例重复发送。已受理的重试直接返回受理结果。
- 提供方明确拒绝的请求可重试；网络超时等无法确认结果时，云端保留 `uncertain` 状态，避免盲目重发。此时核对服务商记录：已受理可把云端该请求记为 `accepted`，确定未发送才可删除该云端请求记录并在后台重试。切勿把未确认结果直接当作未发送。
- 下架、删除、关闭全局推送、设备解绑会取消未发送任务。发送前复查用户状态、登录版本、设备归属和发布状态；已交给系统或服务商的通知无法撤回，点击仍需重新验证内容访问权。
- Android 前台通过推送事件刷新摘要；前台每 60 秒校准。进入后台不使用 JS 定时器收消息，交由 UniPush 和配置的厂商通道接收。通知权限、ROM 限制及厂商配额会影响展示。
- 推送点击的 `noticeId/categoryId` 仅作为导航线索。APP 向后端重新获取详情，必要时等待登录，返回时依次进入所属分类列表和消息页；下架、删除或无权限时不给出正文。

## 接口与验证

- 分类及通知参数增加 `recipientPolicy`、`audienceType`、`recipientUserIds`、`pushEnabled`；发布结果和详情包含 `publicationId`。
- `GET /app/notice/summary` 返回总未读和分类最新标题、时间、未读数；分类列表不再逐个请求最新通知。
- `GET /app/notice/list` 第一页附带 `readPublicationIds`；`POST /app/notice/read/category` 接收 `{categoryId, publicationIds}`，`POST /app/notice/read/one` 接收 `{id, publicationId}`。两个写接口都要求 APP 登录。
- `POST /app/notice/device/bind`、`/unbind` 接收 `{cid}`，以当前会话身份关联设备。
- 后台 `GET/POST /notice/push/config`，`POST /notice/push/test {cid}`，`POST /notice/push/retry {id}`；沿用管理员与只读账号鉴权。
- 后端：`mvnw.cmd -Dtest=NoticeDeliveryTests,NoticeApiIntegrationTests,JwtAudienceTests test`。集成测试需本机已迁移 MySQL 的环境凭据，测试新建数据事务回滚，不调用外部推送。
- APP：`npm run typecheck`、`npm run build:app -- --mode development`、`npm run check:notice-bridge`、`npm run check:notice-ui`。界面脚本使用接口 fixture，不向真实账号发送消息。
- 后台：`pnpm typecheck`、`pnpm build`。

当前仍需外部完成：UniPush 开通、云空间关联、云函数部署、厂商通道及正式包配置；完成后再验收前台、后台、冷启动、权限拒绝和厂商离线送达。
