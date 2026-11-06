# 管理员调整用户积分

## 操作入口与规则

管理后台「用户管理」的 APP 用户和官方账号列表均提供「调整积分」。只有超级管理员可使用；只读账号不显示入口，后端也会拒绝写入及查询内部调整记录。

弹窗展示最新积分和等级，选择增加或扣减、填写正整数数量和必填原因后提交。下方可分页查看人工调整的时间、操作人、数量、前后积分、前后等级及原因。用户详情也展示当前积分。

- 积分沿用 `app_user.experience`，允许范围为 0～1,000,000,000,000。超出范围整笔拒绝，不截断数量。
- 按最新等级配置自动升降级，最低 Lv.1；本次人工调整不触发或撤销跨段奖励，不消耗或恢复每日行为奖励额度。之后的自动行为仍沿用原有跨段奖励和终身去重规则。
- 禁用账号可以调整；不存在或已删除的账号禁止调整。官方账号必须已有未删除的社区身份，不会自动创建社区身份。
- 原因去除首尾空白后须为 1～200 个 Unicode 字符，仅管理员可见。客户端个人经验记录仅展示「管理员调整」及正负数量，不暴露原因或操作人。
- 提交期间禁止关闭和重复提交。失败保留输入并复用请求标识；修改输入视为新操作。成功后清空输入并刷新最新积分及记录。

## 数据库迁移与部署顺序

先完成现有 `p1`～`p19` 迁移，再执行：

```sql
SOURCE doc/sql/p20-experience-adjustment.sql;
```

`p20` 可重复执行，仅给 `user_level_ledger` 新增可空的 `operator_id`、`reason`、`before_experience`、`after_experience`、`before_level`、`after_level`。历史奖励流水保留，用户积分不变。前后等级一并保存，使重复请求在等级配置变更后仍能返回原始结果。

部署顺序为数据库迁移 → 后端 → 管理面板及客户端。旧后端可继续使用新增可空字段后的表。回退应用时保留新增字段及审计数据；回退旧客户端会使人工流水名称及负号显示不正确。

2026-10-22 已按用户授权在项目配置的本机 `127.0.0.1:3306/ciyuantown` 执行 `p20` 迁移。六个字段均验证通过，7 名用户的积分和等级、3 条历史流水均保持不变。迁移前备份和校验报告保存在 `backups/p20-experience-adjustment-20261022-152501`。应用尚未重新部署；其他环境仍须先执行迁移。

H5 构建验证临时使用本机 HTTPS API 地址；正式发布时仍需提供真实的 HTTPS `VITE_API_BASE_URL`。

## 接口

沿用 `{code,msg,data}` 响应及现有后台鉴权。操作人来自登录身份，禁止由请求指定。

| 接口 | 目标 ID | 说明 |
| --- | --- | --- |
| `POST /app-user/adjustExperience` | 社区用户 ID | 增加或扣减积分 |
| `POST /admin-user/adjustExperience` | 官方后台账号 ID | 定位关联社区用户后调整 |
| `GET /app-user/experienceHistory` | 社区用户 ID | 人工调整记录分页 |
| `GET /admin-user/experienceHistory` | 官方后台账号 ID | 关联社区用户的人工调整记录分页 |

POST 示例：

```json
{
  "id": "123",
  "delta": -20,
  "reason": "纠正重复发放的活动积分",
  "requestId": "e18e2f1a-3d51-4b91-a986-0d09d5b5309b"
}
```

`delta` 必须为 JSON 非零整数，绝对值不超过 10¹²；小数、字符串数量、布尔值均拒绝。`requestId` 为 UUID，同一社区用户共用去重空间，两个入口重复提交也只记一次。相同标识的数量、规范化原因或操作人不同则拒绝；相同参数返回原流水结果。已删除目标始终拒绝，包括重试。

成功 `data` 包含 `id`（流水 ID 字符串）、`delta`、`beforeExperience`、`afterExperience`、`beforeLevel`、`afterLevel`。

GET 参数为 `id`、`pageNum`（默认 1，范围 1～100000）、`pageSize`（默认 20，范围 1～100）。返回 `records/total/current/size/pages`，记录按流水 ID 倒序，字段为 POST 结果加 `operatorId`（字符串）、`operatorName`、`reason`、`createTime`。操作人名称读取当前管理员昵称，ID 永久记录。

积分、等级和 `admin_adjust` 流水在同一事务提交。与自动奖励共享用户行锁；官方目标统一按官方账号 → 社区用户 → 等级配置顺序加锁。事务提交后清理用户资料及帖子列表、详情缓存；缓存故障不会将已提交的调整误报为失败。

## 验证

在后端目录运行隔离数据库测试（工具仅创建、修改并销毁专用临时数据库和临时 MySQL 实例）：

```powershell
$env:JAVA_HOME = 'D:/JDK/jdk21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
python scripts/verify-user-accounts.py --mysqld 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqld.exe' --maven D:/apache-maven-3.9.16/bin/mvn.cmd --tests ExperienceAdjustmentTests,LevelIntegrationTests,LevelConcurrencyTests,UserAccountManagementTests,UserDeletionTests
```

共 63 项测试通过：人工调整、范围校验、权限、官方身份、幂等、奖励额度、配置变更、并发、事务回滚、缓存提交时机及现有账号回归。迁移验证同时检查旧奖励兼容和重复执行不覆盖审计数据。

管理面板目录：`pnpm typecheck`、`pnpm build`。客户端目录：`npm run typecheck`，以及配置真实 HTTPS API 地址后的 `npm run build:h5`。

浏览器验证在本地管理面板 9527 和客户端 H5 开发服务 15273 上运行，使用模拟 API，不写入项目数据库：

```powershell
node scripts/verify-experience-adjustment.cjs
node scripts/verify-level-ui.cjs
```

第一条覆盖弹窗输入、详情及记录失败重试、提交后响应丢失重试、编辑后更换请求标识、提交防重、扣减归零、分页、两类账号和只读权限。第二条覆盖 320/390/430 宽度、原有签到与奖励、人工增加和扣减显示及账号切换。测试截图和报告分别位于项目根目录 `artifacts/experience-adjustment` 和 `artifacts/levels`；本次未进行 Android/iOS 真机验收。
