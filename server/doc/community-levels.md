# 社区60级系统

管理员手动增加／扣减积分、权限、流水及 `p20` 迁移见 [管理员调整用户积分](experience-adjustment.md)。

## 使用入口

- 管理后台：侧栏「等级配置」，地址 `/level-settings`。
- 客户端：个人页昵称下方的徽章和长头衔，点击进入「我的等级」。
- 等级页展示经验进度、签到、连续签到天数、今日奖励额度、六个段位、60张徽章及个人经验记录。
- 帖子列表、帖子详情、评论、粉丝与关注列表展示用户当前等级徽章。

## 默认规则

| 行为 | 每次经验 | 每日经验上限 |
| --- | ---: | ---: |
| 每日签到 | 10 | 10 |
| 连续签到每满7天 | 30 | 30 |
| 评论、回复 | 2 | 20 |
| 发布图文、视频、帖子 | 20 | 60 |
| 帖子、评论被其他用户点赞 | 1 | 50 |

每日限额的单位是经验，按 `Asia/Shanghai` 日期重置。0上限或关闭开关停止奖励。额度不足一次奖励时，发放剩余额度。签到在第7、14、21……天获得额外奖励，断签后连续天数归1。

等级L的累计门槛为 `100 × (L−1) + 15 × (L−1) × (L−2)`。Lv.1门槛0，Lv.2门槛100，Lv.60门槛57,230。满级仍可积累经验，累计经验保护上限为10¹²。

默认每10级一段：初旅、循迹、逐曜、织星、耀令、归穹。进入第二至第六段各奖励50经验，首段不奖励。跨段奖励可以引发后续晋级，每段终身最多发一次。

后台可修改六段的区间、头衔、拓展全称、颜色、说明、进入奖励，60级的门槛和徽章，以及五条行为规则的名称、经验、上限和开关。六个区间必须连续覆盖1–60级；1级门槛固定0，后续累计门槛必须严格递增。

## 发放与配置变更

- 无审核发布成功即发经验；待审核内容首次通过审核时发经验。
- 不奖励给自己点赞。帖子和评论共用「内容被点赞」的每日经验额度。
- 取消点赞、删除、下架保留已经发放的经验。重新点赞、重新审核通过不重复奖励。
- 达到限额、关闭规则时也记录事件来源，日后恢复互动不补发旧事件奖励。
- 修改等级门槛保留用户累计经验，重新计算现有等级，允许升降级。配置变更本身不会补发跨段奖励。
- 修改进入段位的奖励值，不会给已经领过该段奖励的人再次发放。
- 奖励、流水和用户等级在同一数据库事务内提交，失败时全部回滚。相同事件唯一键和用户行锁保证并发请求不会重复发放、突破上限。
- 配置包含版本号。另一个管理员先保存后，旧版本保存会被拒绝，当前页面保留修改。

## 数据库迁移

现有项目库已应用这两个迁移。新环境在此前项目迁移完成后，依次执行：

```sql
SOURCE doc/sql/p16-community-level.sql;
SOURCE doc/sql/p17-community-level-config.sql;
```

`p16` 为 `app_user` 增加 `experience`，创建 `user_level_ledger` 和 `user_level_signin`。首次迁移将历史等级转换为相应门槛经验；重复执行不重置经验。`p17` 创建独立的 `community_level_config` JSON配置表，并仅在缺少配置时写入默认值；重复执行保留已有配置和版本。

不扩容或覆盖原有 `system_config.config_value`；其他模块继续使用原来的配置字段。

数据库与JWT凭据沿用现有环境变量或项目启动脚本，未在业务代码中硬编码。生产前端需要配置HTTPS API地址。

## API

所有接口沿用项目的 `{code,msg,data}` 返回结构。缺少登录、错误token与只读权限使用现有鉴权约定。

| 接口 | 权限 | 内容 |
| --- | --- | --- |
| `GET /level/config` | 后台登录 | 完整配置与version |
| `POST /level/config` | 可写管理员 | 提交完整配置及读取时的version；成功返回新版本 |
| `POST /level/badge` | 可写管理员 | multipart的file，返回url |
| `GET /app/level/config` | 公开 | 六个段位、60个等级、行为规则 |
| `GET /app/level/me` | 客户端登录 | 当前经验、等级、头衔、徽章、下一等级、今日签到及额度 |
| `POST /app/level/sign-in` | 客户端登录 | 幂等签到，返回gained和最新progress |
| `GET /app/level/history?pageNum=1&pageSize=20` | 客户端登录 | 仅本人经验流水分页，pageSize为1–100 |

配置字段结构见后台 `src/service/api/level.ts` 与客户端 `types/level.uts`。徽章上传限制为2MB以内、边长不超过2048的有效PNG，解码后重新编码并保留透明通道。

## 徽章与缓存

- 60张默认PNG均为192×192，位于客户端 `static/images/levels/level-1.png` 至 `level-60.png`。
- 后端打包目录为 `src/main/resources/static/uploads/levels`，通过 `/uploads/levels/level-N.png` 访问；更换本地上传目录仍可回退到打包资源。
- 自定义PNG写入配置的上传目录。客户端加载配置徽章失败时回退到随包默认徽章。
- 公共等级配置缓存60秒；重新进入等级页强制刷新。个人进度和经验记录实时请求，并隔离账号切换与迟到响应。
- 仓库根目录 `scripts/extract-level-badges.ps1` 可从原图重新切分；传入 `-Source`、`-Output` 明确源图和输出位置。脚本输出包含预览图，预览不参与客户端运行。

## 验证

隔离数据库测试使用 `scripts/verify-user-accounts.py`，由工具创建并销毁专用测试库，不修改开发库的存储配置。它验证迁移重入、历史经验保留和配置不被重置。

```powershell
# 先在本地配置 DB_USERNAME、DB_PASSWORD、JAVA_HOME 等环境变量
python scripts/verify-user-accounts.py --maven D:/apache-maven-3.9.16/bin/mvn.cmd --tests LevelIntegrationTests,LevelConcurrencyTests,SocialInteractionTests,CommentIntegrationTests,PostIntegrationTests,UserDeletionTests,ChannelPublishAccessTests
```

客户端 `scripts/verify-level-ui.cjs` 使用本地H5预览端口15273、后台端口9527和模拟API，验证交互、三个屏幕宽度、图片回退、历史请求竞争、错误重试与只读权限。它不会冒充真实后端或原生验收；后端业务由MySQL集成测试独立覆盖。

交付截图、徽章预览、PNG压缩包及验证汇总位于仓库根目录 `artifacts/levels`。Android调试基座的只读页面展示和滚动独立记录；不等于正式APK或物理手机全流程验收。
