# uni-app x 5.26 构建与 USB 调试

此文档记录 HBuilderX 5.26 工具链下的构建、调试与签名步骤。

所有纯视觉细节以原生体感为准；功能、入口、文案、布局和业务规则保持不变。

## 工具链与源码

- HBuilderX **5.26.2026091802 正式版**，安装其 uni-app x、UTS 和各目标构建插件。禁止启用临时 `.lang` 开关。
- App 使用蒸汽模式及字节码视图目标；页面和业务模块为 UTS，原生平台插件位于 `uni_modules/ct-platform/utssdk`。
- 工程入口为 `App.uvue`、`main.uts`。H5 的 index.html 引用扩展名省略的虚拟 `/main`，由官方编译器解析 main.uts。
- `npm ci --ignore-scripts` 按 package-lock.json 安装锁定依赖。构建脚本使用 HBuilderX 自带 Node 22.22.2，避免本机 Node 与原生编译器字节码不匹配。
- 其他安装位置先设置 PowerShell `$env:HBUILDERX_HOME='实际 HBuilderX 目录'`；ADB 也可通过 `ADB_PATH` 覆盖。
- 原 Vue 对照包在工程外的 `deliverables/uniapp-x-migration/legacy-source-baseline.zip`；不参与运行或打包。

## 构建

在客户端目录执行：

```powershell
npm run check:architecture
npm run check:request-recovery
npm run check:performance-core
npm run check:native-core
npm run check:native-scroll
npm run check:native-runtime
npm run check:video
npm run check:video:weixin
npm run build:h5 -- --mode development
npm run build:mp-weixin -- --mode development
npm run build:app -- --mode development
npm run build:ios -- --mode development
```

输出位于 `unpackage/dist/build/{h5,mp-weixin,app-android,app-ios}`。这些 App 目录是**编译资源，不是 APK/IPA**。构建脚本保留日志、工具版本、开始/结束时间、源码 SHA256 和诊断门禁结果；源码在编译期间改变或出现编译器诊断时返回非零。

正式资源构建省略 `--mode development`，读取生产环境。生产 API 必须使用 HTTPS；Android USB 本地地址仅用于开发模式。

HBuilderX 5.26 发行菜单中选择 App 云打包/本地原生工程，使用项目真实包名及签名。原生 UTS 插件需要编译进自定义基座及正式包。Android APK 生成后还要检查签名、安装、新装权限和运行；不能将本节操作说明当成已生成安装包。

iOS 的签名、证书、对应原生构建环境和设备分别验收。微信 manifest 中 appid 仍为空；填入真实配置后才能进入开发者工具及真机验收。Android 通过不能替代它们通过。

## USB 真机

1. 启动真实后端及其依赖；确认电脑 8080 端口可用。
2. 手机开启 USB 调试、连接电脑并授权。多个设备时明确指定序列号。
3. Android 开发运行会在只有一台已授权设备时自动建立 `127.0.0.1:18080 -> 电脑8080` 隧道；多台设备可设置 CT_ANDROID_DEVICE_SERIAL。也可执行 `npm run connect:usb -- 设备序列号`；ADB 重启或拔插后重新运行/连接。此规则同样适用于模拟器，不改变正式构建地址。同一台设备被两条传输通道同时列出时（HBuilderX 连 `127.0.0.1:16384`、SDK adb 连 `emulator-5554`），按 `ro.boot.serialno`/`ro.serialno`/`android_id` 识别为同一台并自动建隧道，不再要求手动指定序列号。隧道缺失时客户端所有请求都连不上；uni-app x Android 对连接被拒只回 `server system error`，因此请求封装把它与 `Failed to connect`、超时同等处理：GET 自动重试一次，仍失败则提示可操作的连接信息，不再只显示“网络异常”。
4. HBuilderX 导入客户端根目录，编译包含 ct-platform 插件的自定义调试基座，再运行到该手机。
5. 覆盖全部页面、权限拒绝、断网重试、后台恢复、系统返回、账号隔离及完整发布/互动链路。

## 证据与性能口径

- `check:video` 是模拟原生上下文的控制器单测，验证清晰度、进度保持、降档/恢复、倍速、暂停确认和释放；不验证真实解码、横竖屏或全屏。
- `check:native-scroll` 使用模拟 Element 几何验证菜单高度约束、尺寸变化、销毁保护与短列表最小高度，不验证真实触摸和列表复用渲染。
- `check:native-runtime` 验证调试隧道选择与端口、组合按钮禁用拦截、原生语义更新合并与条件弹窗移除释放；逻辑模拟不代替节点或读屏验收。
- `check:video:weixin` 使用模拟微信上下文验证原生全屏请求/状态回传/超时与退出、封面状态、六档倍速请求及覆盖层异步测量拖动；不证明设备实际接受所有倍率。
- `check:smoke` 访问真实后端，执行 H5 的只读路由及 320/390/430px 检查，不提交发布、评论或点赞，也不是四端全功能验收。
- 正式包性能必须在同一台 Android、同一显示/刷新配置、真实数据和相同操作流程下比较新旧包。保存 APK SHA256、系统版本、显示配置、构建类型及操作录像。
- 分别执行长列表滚动、20 次底栏切换、20 次详情往返和视频往返。使用 Perfetto FrameTimeline/系统帧证据、内存与资源释放记录；不能用 H5 requestAnimationFrame 或 CDP 结果代替原生帧率。
- 点击反馈 P95≤100ms、已访问底栏切换 P95≤150ms、60Hz 接近60fps，都必须有原始采样和时间定义；没有采样就标记待验证。
- 旧 scripts 中保留的 renderjs/CDP/模拟数据检查属于历史工具；其结果不能计入新架构原生验收。

## 视频运行验收待办

微信已接入 VideoContext 原生全屏和 video 内的 cover-view/cover-image 自绘控制层，覆盖清晰度、全部原速度入口、触摸拖动、保存和退出。保持 slot 及其父节点常驻，通过显示样式控制覆盖层，规避微信官方记录的条件 slot 导致覆盖层不显示问题。背景渐变按已确认的视觉范围改为原生半透明底色；图标由原 SVG 生成透明 PNG，没有改用默认控制栏。

微信实际倍速支持、全屏尺寸/安全区、覆盖层滚动及触摸仍待开发者工具和设备验证，不能因模拟 API 接受参数就记为通过。App 已使用 5.26 Element 全屏 API，但 Android/iOS 横竖屏、返回恢复及视频覆盖仍待实际运行。
