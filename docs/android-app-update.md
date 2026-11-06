# Android 应用更新

客户端使用 uni-app x / UVUE，管理后台使用现有 Vue3 / Naive UI，后端使用 Java 21 / Spring Boot。仅新增 Android APK 更新；iOS、H5、微信不会请求 Android 更新接口。

## 启用顺序

1. 在目标数据库执行 `ciyuantoen-back/ciyuantown-back/doc/sql/p24-app-update.sql`。脚本可重复执行，不会自动发布版本；安装包存储复用 P12 的 `media_storage_config` / `media_object`，需已完成原媒体存储迁移。
2. 用 Java 21 构建并部署后端，再构建并部署管理后台。后台“运营管理 → 应用更新”可查看当前版本、草稿和历史版本。
3. 使用包含新版 `ct-platform` 的自定义基座或完整 Android 安装包。新增了 Service、Activity、FileProvider 和权限，**只热更新客户端资源不能替代原生重新打包**。先通过原分发方式让用户安装带此功能的首版，再使用应用内更新发布后续版本。
4. 正式 APK 使用项目真实包名及签名，新版与旧版须可覆盖安装；`manifest.json` 的版本名称、版本号应随发版更新，实际检查以 Android PackageManager 读取的已安装版本号为准。
5. 后台新建版本，填写版本名称、整数版本号及更新说明，保存草稿后上传 APK。后台会从安装包读取并填写包名和版本信息，展示实际大小和 SHA-256；确认后点击发布。

每个新发布的版本号必须大于全部历史已发布版本。发布后安装包、版本名称、版本号及说明锁定；强制开关仍可调整，默认关闭。发布新版本会切换当前版本，历史记录继续保留。下架当前版本后不自动启用历史版本；需要重新发布时创建更高版本号的草稿。

## 上传与存储

- 单个 APK 最大 `500 × 1024 × 1024` 字节；multipart 请求默认最大 510MB。安装包采用流式文件处理，不经过图片压缩、视频转码，也不整体读入 Java 内存。
- Spring 默认配置：`MULTIPART_MAX_FILE_SIZE=500MB`、`MULTIPART_MAX_REQUEST_SIZE=510MB`。已有部署环境变量会覆盖默认值，启用时检查实际生效值。
- APK 使用随机且不可变的存储键；替换草稿 APK 会创建新的对象，避免 CDN 缓存旧包。版本记录保留期间，其引用的文件继续保留；确认删除非当前版本时同步删除其关联 APK。暂未增加自动清理废弃草稿对象的任务。
- 下载地址须允许客户端免登录读取；正式环境使用 HTTPS。对象存储/CDN 的域名和证书也需要在设备上可访问。

在现有 Nginx 反向代理配置中调整以下参数，不要覆盖项目已有的 API 前缀和转发规则：

```nginx
client_max_body_size 510m;
client_body_timeout 1800s;
proxy_read_timeout 1800s;
proxy_send_timeout 1800s;
```

安装包下载需保留 `Content-Length`、`Range`、`If-Range`、`Content-Range`、`ETag` 等请求/响应头。后端本地媒体接口已验证返回 HTTP 206；外部存储/CDN 应单独确认。若服务器忽略 Range 返回 200，客户端会丢弃旧的拼接位置并从头写入，再校验大小和哈希。

```powershell
curl.exe --range 0-1023 --dump-header range-headers.txt --output range-fragment.bin "https://你的下载域名/安装包路径.apk"
```

预期为 `206 Partial Content`、匹配的 `Content-Range` 和 1024 字节响应体。

## 客户端行为

- 冷启动自动检测一次；设置页“检查更新”可手动检查。自动检测网络失败不弹错误，手动检测会提示。红点依据真实更新结果显示。
- 非强制更新可点击“下次再说”，本次进程不再自动弹出，下次冷启动重新提醒。下载后选择“后台下载”可关闭弹窗并继续使用应用。
- 强制更新不能跳过、不能通过返回键或蒙层关闭；导航切换后仍显示阻挡。允许使用系统 Home 或切换其他应用继续下载。“重新检查更新”可获取后台下架或开关调整后的结果，网络失败不会擅自解除已知的强制更新。
- 弹窗和通知都按下载字节数显示进度条及一位小数，例如 `36.7%`。100.0% 后仍需完成 SHA-256、文件大小、包名和版本信息校验，校验通过才允许安装。
- 下载由原生 `dataSync` 前台服务执行。任务信息、部分文件和完整文件存放在应用私有目录；断网自动重试，重试用尽后可点击继续。进程被系统终止后，下次打开可恢复状态并继续下载，不承诺用户强行停止应用后系统仍执行任务。
- Android 13+ 在用户开始下载时请求通知权限。拒绝后下载仍可进行，弹窗提示通知未开启，可返回应用查看进度。
- 前台完成后自动调起系统安装界面；后台完成只发完成通知，点击通知或返回应用后继续安装。系统确认由用户完成。
- 未获得“允许安装未知应用”授权时进入系统授权页，授权返回后继续。取消安装保留已校验 APK，可手动重试，不循环打开安装器。
- 安装器被打开不等于更新成功；重新启动后读取实际安装版本，确认已升级后清理任务和旧安装包。

## 接口

全部响应沿用现有 `{ code, msg, data }` 封装。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/app-releases` | 管理员查看当前、草稿与历史版本 |
| POST | `/app-releases` | 创建草稿 |
| PUT | `/app-releases/{id}` | 修改草稿 |
| POST | `/app-releases/{id}/package` | multipart 字段 `file` 上传 APK |
| POST | `/app-releases/{id}/publish` | 发布并切换当前版本 |
| POST | `/app-releases/{id}/withdraw` | 下架当前版本 |
| DELETE | `/app-releases/{id}` | 删除非当前版本及关联 APK，当前版本需先下架 |
| PATCH | `/app-releases/{id}/force` | `{ forceUpdate: boolean }` |
| GET | `/app/update/check?versionCode=100` | 免登录检测高于已安装版本的当前发布版本 |

草稿字段为 `versionName`、`versionCode`、`notes`、`forceUpdate`。检查接口返回 `{ hasUpdate, release }`，无更新时 `release=null`；有更新时包含版本信息、强制标记、APK URL、包名、字节数及 SHA-256。管理员写操作受现有只读账号拦截器限制。

历史版本列表中的草稿、历史发布版本及已下架版本支持确认后删除。删除会清理版本记录、关联 APK 和媒体存储记录；APK 删除失败时保留版本记录供重试。当前生效版本必须先下架才能删除；删除不会自动启用其他历史版本。

## 资源与验证

当前弹窗插画存放在客户端 `次元町/static/images/update/xingxi-update-cutout.png`，使用透明顶部让星汐与火箭突出蓝色背景上沿，未写入后端。新样式的生成记录和预览见 `artifacts/app-update/reference-style-20261025/`；更新功能验证记录见 `artifacts/app-update/README.md`。

常用检查：

```powershell
# 客户端根目录
npm run check:app-update
npm run check:native-runtime
npm run build:app -- --mode development
npm run build:h5 -- --mode development

# 管理后台根目录
pnpm typecheck
pnpm build

# 后端根目录，使用 Java 21
./mvnw.cmd '-Dtest=AppReleaseTests,AccountSessionTests,StorageFailureTests' test
```

真实 500MB multipart 测试使用独立 H2 / 随机端口 Tomcat 和临时文件，不访问业务数据库：

```powershell
$env:CT_TEST_LARGE_APK='true'
./mvnw.cmd '-Dtest=AppReleaseLargeUploadTests' test
```

正式发布前还需使用真实签名的完整次元町 APK，在目标 Android 真机验证通知、后台限制、安装授权和覆盖升级；独立原生测试包及浏览器预览不等同于正式包验收。
