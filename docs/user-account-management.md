# 用户管理：账号、密码与详情

删除用户功能及 P14 迁移见 [用户删除说明](user-deletion.md)。普通 APP 用户与官方账号支持软删除；删除后允许重新注册，历史内容与身份保持独立。

已实现 APP 用户和官方账号的详情弹窗、编辑弹窗（账号／密码 Tab），以及新建时填写自定义账号。

## 账号与登录规则

- 新账号：4–20 位字母、数字或下划线，允许纯数字，大小写不区分。
- APP 用户的 QQ 号和邮箱保持不变。未设置自定义账号时，密码登录使用 QQ 号；设置后密码登录仅接受新账号，QQ 验证码登录保留。
- `login_account` 统一登记普通用户有效登录账号与全部后台账号。账号唯一，`owner_type + owner_id` 唯一；官方社区身份不重复登记。
- 新注册遇到已有纯数字登录账号时提示联系管理员，不建立另一人的身份。
- 官方社区身份通过 `app_user.official_admin_id` 关联。官方改名不会改变社区用户 ID，凭据以 `admin_user` 为准。
- 密码：8–20 位、不含空白，BCrypt 存储。管理员设置／重置不需要旧密码；响应和日志不包含密码。
- 账号／密码修改及官方账号启停都会更新会话版本。版本以数据库为权威来源，防止 Redis 写入失败或并发回调导致旧凭据恢复；提交后同步 Redis 并清理资料缓存。

## 管理接口

| 接口 | 入参 | 权限 |
| --- | --- | --- |
| `POST /app-user/create` | `qqNumber`、可选 `account` / `nickname` / `password` | 超管 |
| `POST /admin-user/create` | 完整 `account`、`password`、可选 `nickname` | 超管 |
| `POST /app-user/updateAccount` | `id`、`account` | 超管 |
| `POST /admin-user/updateAccount` | `id`、`account` | 超管，仅官方目标 |
| `POST /app-user/resetPassword` | `id`、`password` | 超管 |
| `POST /admin-user/resetPassword` | `id`、`password` | 超管，仅官方目标 |
| `GET /app-user/detail?id=…` | 社区用户 ID | 已登录后台账号 |
| `GET /admin-user/detail?id=…` | 官方账号 ID | 超管 |

APP 用户列表中的官方社区身份编辑统一修改关联官方账号。详情响应使用独立 VO；未建立社区身份的官方详情返回 `community: null` 及零统计。

详情统计来自数据库关系记录：已发布帖子数、粉丝数、关注数、已发布帖子获赞数，以及用户收藏的已发布帖子数。APP 密码登录继续使用原请求字段 `qqNumber` 承载有效登录账号。

## 上线顺序

1. 暂停后台和 APP 写入并备份数据库；现有库须已完成 P1–P10 迁移。
2. 使用具备 DDL 和存储过程权限的数据库账号执行 `ciyuantoen-back/ciyuantown-back/doc/sql/p11-user-account-management.sql`，不要添加 `--force`。
3. 脚本先检查官方关联异常、无关联的非 QQ 社区身份和全局重名。存在问题时输出记录并停止，人工处理后重试；不自动改名或合并账号。
4. 检查 `login_account` 登记及 `official_admin_id` 绑定，然后部署后端、管理端和 APP，再恢复写入。旧形状的用户资料缓存会自动回源刷新。

该脚本只执行一次。MySQL DDL 不具备整体事务回滚能力；DDL 阶段异常时应保持停写，按备份恢复或核对已完成步骤后修复。官方账号创建入参从数字 `suffix` 改为完整 `account`，需与新管理端同时更新。

## 验证

后端脚本使用独立临时数据库；加 `--mysqld` 可以启动独立 MySQL 实例，测试后删掉临时数据库并停止实例，数据目录留在 `target/` 供排查。

```powershell
$env:JAVA_HOME='D:\JDK\jdk21'
python scripts/verify-user-accounts.py --mysql 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --mysqld 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe' --maven 'D:\apache-maven-3.9.16\bin\mvn.cmd'
```

管理端运行 `pnpm typecheck`、`pnpm build`。启动管理端 Vite 服务后，在 APP 项目运行 `node scripts/verify-user-management.cjs`（默认 `http://127.0.0.1:5186`，可设置 `ADMIN_UI_TEST_URL`）；该检查使用模拟 API，不改业务数据。

APP 运行 `npm run typecheck`、`npm run build:h5`。H5 生产构建需配置实际的 HTTPS `VITE_API_BASE_URL`；本次构建验证使用 `https://account-build-test.invalid`，生成物仅供验证，不用于部署。
