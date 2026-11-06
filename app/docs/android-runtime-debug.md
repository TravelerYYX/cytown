# Android 触摸、刷新与运行时异常排查

## 已修复的代码问题

- App 所有 20 个页面均不启用 `disableScroll`。该配置会让 App 运行时在全局 `document.touchmove` 被动监听中调用 `preventDefault()`。页面继续使用固定容器、内部滚动组件和 `app-plus.bounce: none`；H5 和小程序保留原设置。
- 首页刷新期间保留已成功加载的帖子和频道组件，成功后替换第一页；失败后的重试重新请求第一页。重复刷新合并，旧分页响应通过请求代次丢弃，刷新期间不并发重新校验列表。
- 视频详情返回先等待 renderjs 停止播放回调的确认，再销毁逻辑层页面；同时拦截已经移除的 DOM、已暂停和已销毁实例的回调，防止旧实例再次创建播放器。覆盖视频返回按钮和 Android 返回键。

`nodeId` 栈对应运行时的 `onWxsInvokeCallMethod`：renderjs 的 `callMethod` 到达时，页面容器已经移除。它与首页的网络连接失败不是同一条调用链。

## 网络诊断结论与边界

2026-10-11 本次复现补充：后端进程持续运行，电脑访问 `127.0.0.1:8080` 和 `192.168.1.10:8080` 均为 HTTP 200。手机最初请求成功，连续 5 次下拉刷新成功；随后再次出现 `Failed to connect`。故障期间手机访问路由器 `192.168.1.1` 成功，但访问电脑返回 `Destination Host Unreachable`，`ip neigh show 192.168.1.10` 显示 `FAILED`/`INCOMPLETE`；电脑访问手机也超时。由此定位到局域网邻居发现/设备互通链路，尚不能确定具体是路由器还是无线网卡导致，不能宣称修改请求代码已消除该故障。

在上述故障仍持续时，临时建立 ADB USB 反向转发：同一手机请求 Wi-Fi 地址仍返回状态 0，通过 USB 请求同一接口返回 HTTP 200（81 毫秒）。诊断后已移除临时转发，未改变正式 API 地址。

用户随后选择使用 USB 开发连接。Android development 模式现读取 `VITE_ANDROID_DEV_API_BASE_URL=http://127.0.0.1:18080`，通过 `npm run connect:usb` 建立 `adb reverse tcp:18080 tcp:8080`；USB 拔插或 ADB 重启后需重新执行。已重新编译并同步手机，USB 接口返回 HTTP 200，连续 5 次真机下拉刷新成功，当前构建日志未出现请求失败。H5、iOS 和生产构建仍使用常规 API 配置。这是绕过故障链路的开发方案，Wi-Fi 故障原因仍未确定。

请求封装现对 GET 的明确超时或 `Failed to connect` 自动重试一次：首次超时 15 秒，等待 300 毫秒，重试超时 5 秒。重试成功不弹错误，持续失败显示超时或无法连接服务器；POST、上传、主动取消和 HTTP/业务错误不自动重放。会话代次变化时取消尚未发出的重试。使用 `npm run check:request-recovery` 验证恢复、重试次数、错误提示及旧会话保护。

2026-10-11 真机复现到原生请求错误：

```text
request:fail abort statusCode:-1 Failed to connect to /192.168.1.10:8080
```

同一时刻，从手机通过独立的 `plus.net.XMLHttpRequest` 请求 Wi-Fi 地址返回状态 0；通过临时 ADB USB 通道请求同一后端返回 HTTP 200。因此连接失败发生在手机访问本地后端的网络路径，不能归因于首页清空列表、passive 警告或视频回调。

此前排查时电脑已有指向当前 JDK 21 的 OpenJDK 防火墙放行规则。新增规则停用时连接也成功，没有证据证明新增规则解决了断连；当时临时规则和 USB 转发已撤销，开发地址保持 `http://192.168.1.10:8080`。随后用户选择 Android USB 开发，最新配置及验证见本节开头。

用户确认复现前后没有切换网络或 FlClash，电脑一直使用规则模式。实际运行配置同时启用了 TUN 和自动路由；系统代理绕过列表已包含 `192.168.*`。当前 Windows 路由中 `192.168.1.0/24` 指向 WLAN，成功请求的抓包可见手机与后端在 WLAN 上完成 TCP 握手。因此目前没有证据证明 FlClash 是故障原因，没有修改代理模式、节点或持久配置。TUN 自动路由与自定义排除网段的作用见 [Mihomo 官方文档](https://wiki.metacubex.one/config/inbound/tun/)。

最新增量同步后的真实下拉刷新、视频播放快速返回和 Android 返回键测试通过，日志未再出现连接失败、`nodeId`、`uni is not defined`。但间歇性 Wi-Fi 连接失败的确切原因尚未定位，不能把当前测试通过视为永久消除。开发环境失败日志记录请求方法、完整后端路径、耗时（毫秒）和原始 `errMsg`，不记录请求参数或凭证。

同步时曾出现 `uni is not defined` 和 `__uniappview.html` 无法打开；重新启动兼容的 HBuilderX 5.15 调试基座后没有再次复现。没有修改运行时全局初始化或屏蔽这些错误。

## 验证

```text
npm run typecheck
npm run check:scroll-config
npm run build:app -- --mode development
npm run build:h5 -- --mode development
npm run check:scroll-config -- --app-output dist/build/app
npm run check:scroll-config -- --app-output unpackage/dist/dev/app-plus
npm run check:home-refresh
npm run check:video
```

配置检查覆盖 App、App-Plus、H5、微信小程序各 20 个页面。构建使用已配置的 development 环境；生产 API 地址尚未配置。

浏览器回归覆盖失败保留列表、成功替换、第一页重试、刷新合并、迟到分页、频道变化，以及播放器暂停确认、DOM 移除时的迟到事件和旧实例销毁。它们不能替代 Android 真机验收。

真机记录位于被忽略的 `artifacts/scroll-regression/`：`home-refresh-result.json`、`video-navigation-result.json` 及对应日志。最新刷新记录是连续 5 次成功，视频记录是连续 5 次返回成功，包含 Android 返回键。
