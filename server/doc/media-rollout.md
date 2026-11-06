# 存储与媒体部署

运行环境：Java 21、MySQL 8、FFmpeg/FFprobe（包含 libx264、AAC、WebP、GIF 与 drawtext）。后端 `.env` 配置 `FFMPEG_PATH`、`FFPROBE_PATH`、`FFMPEG_FONT_PATH`；头像和运营资源也通过统一存储上传。

## 数据库与历史清理

`doc/sql/p12-media-storage.sql` 新建存储配置、对象索引和持久转码任务，并增加素材版本字段。可重复执行，不删除业务数据。使用具备 CREATE、ALTER 权限的迁移账号执行；应用账号仍仅需 SELECT、INSERT、UPDATE、DELETE。

历史清理是独立工具，执行前停止后端，避免转码和发布写入。凭据从 `.env` 或进程环境读取，不写入清单。Windows 可用已有 DPAPI 凭据：

```powershell
./scripts/media-maintenance.ps1 -Action prepare -Path backups/media-cleanup-日期
./scripts/media-maintenance.ps1 -Action apply -Path backups/media-cleanup-日期/manifest.json
./scripts/media-maintenance.ps1 -Action migrate
```

`prepare` 备份完整数据库、目标文件，生成包含对象路径和 SHA-256 的 `manifest.json`。`apply` 再次校验备份、源文件和保留资源的引用，按清单 ID 删除历史记录、清理通知的无效帖子引用及相关 Redis 缓存；保留用户、关注关系、头像、配置和运营资源。`progress.json` 记录数据库、文件及缓存步骤，可使用相同清单恢复。清单生成后新增的数据不属于清理范围。备份必须位于上传目录之外。

恢复时停止后端，将 SQL 备份导入相同数据库，再按清单的 `path`／对象路径恢复文件。备份含账号数据，应按数据库备份权限保管。云对象备份经公开域名读取；独立清理工具对云凭据的 AES-GCM 解密需要 Python `cryptography`。

本次清理备份：`backups/media-rollout-20261010/`。清理前 12 个帖子、14 条评论、7 个素材和 3 个草稿；清理后历史表为空，保留表数据指纹一致。

## 后台配置

管理后台分别提供“存储配置”“图片压缩”“视频转码”页面。默认本地目录 `UPLOAD_DIR`（未设置为后端 `uploads`），可填写独立访问域名；留空时通过 `/uploads/storage/{配置ID}/...` 浏览。

OSS 配置地域、完整 Endpoint（包含 HTTPS）、Bucket、AccessKey、SecretKey、访问域名。七牛配置地域（z0/z1/z2/na0/as0）、Bucket、AccessKey、SecretKey、访问域名。保存前可点击连接检测，检测临时对象的写入、读取/状态和删除。服务端加密两个密钥，读取接口仅返回脱敏值；留空 SecretKey 保留当前密钥。

配置版本不可变，每个对象记录所属配置和对象路径；切换仅影响新上传。保留旧 Bucket、域名和凭据，旧媒体继续从原配置访问。使用稳定的 `MEDIA_CONFIG_KEY` 保管加密密钥；未设置时使用现有 `JWT_SECRET`。上线后不要改变用于既有配置加密的密钥。

### 远程 FTP

“存储配置”选择“远程 FTP”，填写主机（主机名或 IP，不含协议和端口）、端口（默认 21）、用户名、密码、根目录（默认 `/`）和 HTTP/HTTPS 访问域名。仅支持普通 FTP，使用被动模式、UTF-8 和二进制传输。FTP 服务需开放控制端口及配置的被动数据端口范围，后端需能访问这些端口。连接超时为 15 秒，控制与数据读取超时为 60 秒。

根目录是 FTP 账号登录后看到的绝对路径，必须提前存在，账号需具备其下创建子目录、上传、读取、重命名、列出文件和删除权限。例如账号被限制在服务器 `/srv/site`，后台根目录填写 `/media`，则实际文件位于 `/srv/site/media`。程序自动创建对象子目录，上传先写唯一 `.part` 文件，传输成功后重命名；失败时尝试清理临时文件。

FTP 服务之外需自行配置 HTTP 服务或 CDN。根目录 `/media` 与访问地址 `https://cdn.example.com/files` 对应时，对象 `posts/a.png` 写入 FTP `/media/posts/a.png`，公开地址为 `https://cdn.example.com/files/posts/a.png`；访问地址不会再次拼接 FTP 根目录。图片、视频和 HLS 清单、分片均通过该地址直接读取。HTTP 服务需支持 GET、HEAD、Range，正确配置下文 CORS 和媒体 MIME；FTP 本身不能设置 HTTP Content-Type。使用 HTTPS 页面时应配置 HTTPS 资源地址。

FTP 密码通过现有 AES-GCM 机制加密保存，接口始终返回空密码；编辑时留空保留当前 FTP 密码，输入密码的前后空格原样保留。首次切换到 FTP 必须填写密码。配置保存在现有 JSON 字段中，无新增数据库迁移，旧配置仍可读取。切换后需保留旧 FTP 服务器、账号、根目录及 HTTP 映射，以支持旧资源读取、下载、转码和删除。

“连接检测”使用未保存的表单数据，验证临时文件上传、FTP 下载内容、HTTP 公开读取和删除；删除失败也会报告检测失败，需检查权限并清理 `connection-tests/` 下残留的检测文件。系统诊断只验证 FTP 登录和根目录访问，不上传文件。

管理端对连接检测单独设置最长 5 分钟等待，其他接口仍使用默认超时。FTP 检测失败会显示具体阶段或安全的 FTP 响应码：登录失败检查账号；根目录返回 550 时检查登录后可见的路径；上传/读取超时检查被动数据端口；HTTP 公开读取返回 404 或内容不一致时，检查访问域名是否指向 FTP 根目录。FTP 能登录和传输文件，不代表 HTTP 网站映射已经配置成功。

Bucket/CDN 使用公开读取。为 H5 和 Android WebView 设置 CORS：允许实际客户端来源的 GET、HEAD，允许 Range，并暴露 Content-Type、Content-Length、Accept-Ranges、Content-Range；不要允许匿名写入。TS 使用 `video/mp2t`、m3u8 使用 `application/vnd.apple.mpegurl`，保留清单相对路径。支持 HTTPS 的生产环境应使用 HTTPS 访问域名。

图片默认封面最长边 1280、WebP 质量 78，预览最长边 2560、质量 85，PNG 压缩 6，缓存上限 3GB。视频默认 4 秒分片、最高 30fps、CRF 23、音频 128kbps，各档码率 5000/2800/1400/800kbps，任务并发 1；配置输入有边界校验。视频封面也使用后台封面尺寸和 WebP 质量。

## 接口与处理

- 管理端：`GET/POST /media-config/storage`、`POST /media-config/storage/test`、`GET/POST /media-config/processing`。
- 客户端缓存配置：`GET /app/media/config`。
- 原有素材上传和查询接口扩展 `previewUrl`、`originalUrl`、`hlsUrl`、`qualities`、`status`、`resourceVersion`。
- 作者重试：`POST /app/publish/assets/{id}/retry`。
- 保存：`GET /app/post/{postId}/media/{mediaId}/download?version=preview|original`，仍校验登录、下载权限和水印。

视频任务保存配置快照和工作租约，重启后恢复过期任务。分片和子清单全部上传完成后发布主清单，再原子更新素材为 ready。processing 视频可发布；failed 素材须由作者重试成功后再提交。播放不持久缓存视频，图片按资源版本和图片版本持久缓存；H5 在浏览器额度紧张时提前淘汰，缓存故障退回正常浏览。

## 自动验证

```powershell
python scripts/verify-media.py --mysql "MySQL客户端路径" --mysqld "MySQL服务端路径" --maven "Maven路径"
# 在客户端目录执行
npm run typecheck
node scripts/verify-media-ui.cjs
node scripts/verify-media-cache.cjs
node scripts/verify-media-adaptive.cjs
node scripts/verify-media-admin.cjs
npm run build:h5 -- --mode development
npm run build:app -- --mode development
```

Java 验证器只在临时 MySQL 实例中测试，不清理项目数据库。浏览器验证器使用真实 FFmpeg HLS 夹具和受控 API 数据，截图及结果位于客户端 `artifacts/media-ui/`。App 构建为 uni-app 资源，Android 安装包和真机验收需 Android 构建环境及设备；真实 OSS、七牛联调需对应测试 Bucket 和权限。

FTP 自动验证：后端使用 Java 21 执行 `./mvnw.cmd -Dtest=FtpStorageTests,StorageFailureTests,DiagnosticTests test`，测试启动仅监听本机的临时 FTP 与 HTTP 服务，不需要真实服务器或数据库。管理端启动后，在客户端目录执行 `node scripts/verify-ftp-admin.cjs`，验证 FTP 表单、检测、保存、密码清空和只读权限，截图和结果位于 `artifacts/media-ui/`。真实 FTP 联调需提供测试账号和对应 HTTP 访问地址。

参考：[FFmpeg HLS](https://ffmpeg.org/ffmpeg-formats.html#hls-2)、[hls.js 起播档位](https://hlsjs.video-dev.org/api-docs/hls.js.hls.startlevel)、[uni-app renderjs](https://zh.uniapp.dcloud.io/tutorial/renderjs.html)。


## 本次部署状态

历史备份、清理和保留数据校验已完成。2026-10-10 已使用用户提供的有效迁移凭据完成项目数据库 P12 升级，三张媒体表及五个素材字段均已确认存在。正在运行的后端已通过真实 HTTP 图片上传验证：素材状态 ready，WebP 封面、同格式透明 PNG 预览和原图均可读取，原图字节未修改。验证使用的临时用户、素材记录和文件已清除。

上传曾因数据库缺少媒体表而返回“文件写入所选存储失败”。代码现已区分缺失结构、数据库权限和存储写入失败，日志仅记录提供方、SQL 状态、错误码或异常类型，不输出密钥。两项新增测试覆盖缺失表提示和对象记录写入失败后的文件清理，后端 JAR 已重新构建；运行中的 IDEA 进程在下次重启后加载新增诊断提示。外部 DPAPI 凭据文件未修改。

OSS／七牛真实 Bucket 联调、Android 安装包与真机验收仍需对应环境。自动验证在隔离数据库及 H5 浏览器中完成，不代表这两项外部环境验收已完成。
