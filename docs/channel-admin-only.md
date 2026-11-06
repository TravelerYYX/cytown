# 频道“只能管理员发布”

后台频道新增、编辑弹窗提供“只能管理员发布”开关。默认关闭，开启后仅允许关联有效、启用且类型为官方账号的前端社区身份发布。超级管理员的前端登录能力不在本次修改范围内。

普通用户的发帖版区列表不再包含受限频道，首页频道与帖子浏览保持原有行为。默认频道不可发布时，按频道排序选择第一个可用频道；无可用频道时显示“暂无可发布版区”并禁用发布，仍可保存草稿。

## 接口与权限

- 频道实体、管理接口及前端类型增加 `adminOnly`，数据库为 `home_channel.admin_only BOOLEAN NOT NULL DEFAULT FALSE`。
- 新建频道省略字段时默认关闭；更新省略字段时保留原设置，兼容旧后台客户端。快捷启停提交完整频道记录。
- `GET /app/publish/config` 保持接口结构，根据服务端当前官方身份过滤 `channels`，重新计算 `defaultChannelId`；空列表返回 `null`。客户端不能指定官方身份。
- 发帖配置使用 `channel-access-v1:official` 和 `channel-access-v1:regular` 两种缓存键，身份判断在缓存查找前执行；频道保存清除所有相关配置缓存。
- `POST /app/publish/submit` 对三种发布模式重新查验频道权限，普通用户提交受限频道返回业务码 `1000`，提示“该频道仅管理员可发布”。权限校验发生在帖子、媒体关联和草稿删除之前。
- 发帖页每次显示时刷新配置，保留正文、素材和创作设置；旧草稿引用无权发布的频道时自动切换并提示。配置加载失败时禁用发布，保留内容，返回页面后可重试。

## 更新顺序

1. 备份当前数据库，用具备 ALTER 权限的账号执行 `ciyuantoen-back/ciyuantown-back/doc/sql/p13-channel-admin-only.sql`。脚本可重复执行，不修改现有频道的发布限制，不按名称自动开启“官方”频道。
2. 更新后端，再更新管理端与前端。生产构建继续使用项目实际 HTTPS API 地址；验证构建使用的 `https://api.example.invalid` 是测试占位地址，不能部署。
3. 在后台编辑需要限制的频道并开启开关。用普通账号和官方账号核对版区列表与发布结果，核对首页仍可浏览频道。

2026-10-10 已按用户授权对本机 `127.0.0.1:3306/ciyuantown` 执行 P13 迁移。执行前备份频道表至后端 `backups/channel-admin-only-20261010-222650/home_channel-before.sql`；执行后核对 `admin_only` 为非空、默认 0，原有 5 个频道的全部已有字段保持一致，新增发布限制均为关闭。验证记录位于同目录 `verification.json`。未重启现有后端，也未发布到远程服务。

## 验证

在后端目录运行以下命令，使用独立临时 MySQL 实例和数据库，验证完成后删除临时数据库并停止实例：

```powershell
$env:JAVA_HOME='D:/JDK/jdk21'
python scripts/verify-user-accounts.py --mysql 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe' --mysqld 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqld.exe' --maven 'D:/apache-maven-3.9.16/bin/mvn.cmd'
```

共 22 项测试通过，其中频道权限 7 项，账号管理 11 项，JWT 身份与会话 4 项。频道测试启用真实 Spring 缓存拦截并使用内存缓存，覆盖官方/普通缓存隔离、身份禁用/变更/关联缺失、受限默认频道、无可用频道、三种模式的提交拦截与官方发布、设置关闭后的恢复，以及创建和快捷启停持久化。迁移测试验证默认关闭和重复执行保留原设置。

管理端 `pnpm typecheck`、`pnpm build`，前端 `npm run typecheck`、`npm run build:h5`、`npm run build:app` 均通过。前端构建使用测试 HTTPS API 地址，验证后未部署这些产物。

在前端目录运行浏览器检查，`UI_TEST_URL` 指向前端开发服务器或 H5 构建静态服务器，`ADMIN_UI_TEST_URL` 指向管理端开发服务器；接口使用测试桩，不向真实服务保存：

```powershell
$env:UI_TEST_URL='http://127.0.0.1:15371/'
$env:ADMIN_UI_TEST_URL='http://127.0.0.1:15372'
$env:UI_BROWSER_PATH='C:/Program Files/Google/Chrome/Application/chrome.exe'
node scripts/verify-publish-ui.cjs
node scripts/verify-channel-admin.cjs
```

发帖界面覆盖六种手机宽度、三种模式、版区选项、旧草稿回退并保留素材、初次慢请求与草稿恢复顺序、返回页面后权限变化、配置请求失败禁用发布、无可用版区时保存草稿及重新开放后的恢复。后台界面覆盖编辑保存、重新加载、快捷启停保留设置、新建默认关闭及新建受限频道。结果和截图分别位于前端 `artifacts/publish-ui` 与 `artifacts/channel-admin`。
