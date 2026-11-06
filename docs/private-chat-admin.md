# 后台用户私聊管理

入口：**内容管理 → 用户私聊**，路由 `/chat-manage`。仅超级管理员可查看会话、媒体和操作；服务端逐接口校验管理员身份，官方只读账号及APP令牌不能访问。

## 页面和管理规则

- 列表展示双方头像、昵称、有效登录账号、限制状态及最近消息，可按任意一方昵称或账号搜索，每页20条。
- 对话按时间展示，每页最多50条，“更早一页／更新一页”替换当前页；查看不影响双方已读状态。图片点击查看，视频先加载封面、点击后加载视频；翻页会取消下载并释放临时媒体地址。
- 支持永久删除单条及清空整段会话。清空以提交时的消息ID为截止点，新消息保留，关注关系及原有继续聊天资格保留。
- 账号封禁复用原用户状态及会话失效逻辑；永久私聊封禁只限制该用户向任何人发送，仍可接收、查看。
- 限时禁言可选全部私聊或仅向当前对方发送；快捷时长为10分钟、1小时、1天、7天，支持自定义正整数分钟及提前解除。
- 各种限制独立保存，按服务器时间到期；解除一项不会覆盖其他限制。所有消息类型均在服务端发送提交时校验。

## 删除和同步

清空会话立即更新 `cleared_through`，所有用户端、后台历史及鉴权媒体读取均屏蔽截止点内的记录；持久化任务每批删除100条，服务重启后继续处理。单条删除直接在事务内删除正文、回执、推送任务及资源记录。

`chat_deleted_message` 只保存消息、会话、发送者和客户端发送标识，供离线同步和防重使用，不保存聊天原文、媒体地址或密钥。`has_history` 保留既有会话继续聊天资格。

云端加密分块、图片表情副本、视频及封面进入原资源清理队列，`force_delete` 保证管理员私聊删除不受普通资源删除开关影响。只删除聊天独立副本，保留后台表情素材。任务状态区分清理中、自动重试和已完成；云端文件尚未全部清理完成时不会报告已完成。

H5和Android客户端消费 `deleted`、`cleared` 事件，持久化删除标记及清空截止点，移除对应消息和应用内媒体。失败的本地媒体清理进入持久化重试队列。整段清空使用截止点合并文字消息事件，客户端跳过已清空且没有本地缓存的旧消息，避免逐条重新读取大量历史。迟到的分页或发送响应不能恢复已删除内容。已保存到系统相册的文件不处理；离线设备在联网且运行升级客户端后补同步。

## 接口

以下接口均位于 `/chat-admin`，时间为毫秒，业务ID使用字符串：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/conversations?keyword=&pageNum=1&pageSize=20` | 查询会话列表；单页最多100条 |
| GET | `/conversations/{id}` | 双方身份及限制详情 |
| GET | `/conversations/{id}/messages?before=0&after=0&limit=50` | 消息分页；before和after互斥，返回older/newer游标 |
| POST | `/conversations/{id}/messages/{message}/delete` | 永久删除单条，返回删除任务 |
| POST | `/conversations/{id}/clear` | 提交截止点清空任务 |
| GET | `/conversations/{id}/jobs` | 最近20个删除任务 |
| GET | `/jobs/{id}` | 清理状态、剩余文件数及错误提示 |
| POST | `/restrictions` | userId、conversationId、action、可选minutes |
| POST | `/accounts/status` | userId、status（0启用、1封禁） |
| GET | `/assets/{id}` | 鉴权媒体元数据 |
| GET／HEAD | `/assets/{id}/content` | 鉴权解密流，支持Range，禁止公共缓存 |

限制的 `conversationId=0` 表示该用户全部私聊；具体会话ID表示单向会话禁言。action为 `block`、`unblock`、`mute`、`unmute`，永久私聊封禁仅支持全局。管理操作写入 `chat_admin_audit`，不写入聊天内容。

## 数据库与部署

P28迁移：`ciyuantoen-back/ciyuantown-back/doc/sql/p28-chat-admin.sql`，须在P27后执行。新增会话摘要字段、删除同步记录、限制、持久化删除任务、审计及票据过期索引，并扩展原文件清理队列。

工作区数据库已备份并执行P28。备份与校验信息位于 `artifacts/chat-admin-20261026/migration-result.json`；SQL备份含业务数据，应留在受控本地目录。迁移没有删除真实用户消息。

重复检查或其他同结构环境使用：

```powershell
# 通过进程环境设置 CT_DB_PASSWORD，脚本不保存或打印密码。
python scripts/migrate-chat-admin.py
```

脚本从后端 `.env` 解析目标DB_URL，备份成功后才执行迁移，已有完整P28则跳过，发现部分迁移会停止。部署顺序为数据库、后端、管理后台、H5及Android客户端。旧程序回滚时保留新增表和字段；永久删除内容的恢复依赖备份。

## 验证

- `ChatAdministrationTests`：权限、有效账号、稳定双向分页、限制叠加及到期、各类消息发送限制、删除防重、封禁用户历史及清空／发送竞争。
- 原 `ChatTests`、`ChatMediaTests`、`ChatVaultTests`、`ChatPushTests` 回归聊天、媒体、加密读取及通知防重、撤销取消、换绑和重试。
- `ResourceCleanupIntegrationTests`：隔离MySQL及本地测试存储，覆盖总开关关闭时强制删除、故障后重试、真实HTTP鉴权，以及发送等待封禁事务提交时必须读取最新限制的MySQL锁等待验证。
- `node --test scripts/check-chat-account.cjs`：13项账号隔离、删除标记、迟到历史和清理重试验证。
- `scripts/verify-chat-admin.cjs`：隔离真实MySQL和后端、管理后台浏览器、双H5账号，覆盖万条分页、离线删除、媒体及分批清空。设置 `CHAT_TEST_DB_PASSWORD`；设置 `CHAT_TEST_ANDROID=true` 时额外验证已连接Android调试应用，并恢复原账号和USB转发。
- 管理后台 `pnpm typecheck`、`pnpm build`；H5、Android使用项目固定HBuilderX工具链构建。Android当前验收为开发调试基座，发行APK仍使用项目既有打包流程和实际生产HTTPS地址。

验收结果及截图位于 `artifacts/chat-admin-20261026/`。端到端脚本临时构建连接测试后端的H5包，结束后应重新执行正常环境H5构建，再用于交付。

最终验收记录：48项后端检查、13项客户端状态检查、11项真实浏览器端到端检查通过，浏览器运行错误为0；另有4项Android原生功能验收通过。后台类型检查和生产构建通过；H5正常开发环境构建与Android开发资源构建通过，二者源码指纹一致。详见 artifacts/chat-admin-20261026/verification-summary.json。测试数据库已清理，Android调试端已退出临时测试账号，USB转发恢复至正常后端。本地临时测试存储目录的递归清理被自动审批策略拦截（blocked by policy），目录已保留。
