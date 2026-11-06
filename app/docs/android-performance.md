# Android 前端流畅度优化与验证

本轮保留 uni-app Vue3、原有页面和后端协议。Android 使用 renderjs 处理滚动与列表视口，H5 直接运行同一视图驱动，微信小程序保留普通列表和滚动事件实现。

## 已实施

- 首页优先挂载，其余主视图首次访问后常驻；再次切页保留列表、频道及位置。页面隐藏或 APP 进入后台时暂停刷新任务、新闻轮播、互动动画和媒体处理轮询。
- 首页单列、双列瀑布流、关注、我的采用窗口渲染：可视区及前后各两屏，未挂载部分以高度占位。高度估算经实际测量校正，两列共享阅读锚点；分页保持已有列归属。
- 滚动视口和个人页顶部透明度在视图层按帧处理。普通滚动仅在窗口边界、头部折叠状态、开始/结束时同步必要信息；恢复位置等待视图层布局，而非依赖逻辑层 nextTick。
- 帖子对象由列表、详情持有，虚拟卡片卸载不清除互动状态；释放最后一个数据持有者后移出全局注册表。作者索引替代每次注册遍历全部帖子。
- 列表有效期 60 秒，首页配置及个人入口 5 分钟。后台只校验第一页，按 ID 更新已有对象；新顺序留待主动刷新，避免改变阅读位置。同一查询合并请求，滚动时暂缓后台校验。
- 图片按窗口加载，默认最多并发 3 个缓存下载，可见图片优先，同资源请求合并。丢弃无消费者的排队任务；保留资源版本隔离、LRU、浏览器配额回退和清缓存的代次保护。
- 原生缓存索引只在初始化读取，命中和访问时间合并异步落盘，APP 隐藏时刷新元数据；缓存总字节数常驻内存，未超限时不遍历排序。H5 检查缓存大小和回收时只保留元数据，不创建包含全部 Blob 的数组。
- 首页模糊背景、个人页静态背景改为预处理图片。源图保留，生成命令为 `python scripts/build-performance-backgrounds.py`（需要 Pillow）。320/390/430px 的对比截图位于下述产物目录。

## 同流程对比

数据来自桌面 Chrome、390×844、H5 开发环境，使用相同 300 条混合图文/长图/视频封面和 20 次「关注→首页」切换。优化前源码快照在独立 15274 端口运行，优化后使用 15273。浏览器结果不代表 Android 正式包帧率。

| 指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 首页已加载帖子 | 300 | 300 |
| 首次采样挂载卡片 | 300 | 约 15（瀑布流）；约 9（单列） |
| 首次采样 DOM 节点 | 7907 | 约 560（瀑布流） |
| 首页分页请求总数 | 630 | 30 |
| 首页配置请求 | 21 | 1 |
| 关注分页请求 | 20 | 1 |
| 点击至下一动画帧 P95 | 74.5ms | 14.4ms（该次采样） |

首屏时间本轮开发环境采样约 0.3–0.4 秒，没有足够证据判断冷启动已稳定改善。JS 堆采样受垃圾回收和首次编译影响，原始结果保留，不据此承诺固定内存降幅。

## 功能与真机覆盖

- 浏览器：三个帖子页面分别加载 300 条数据，快速跳到多个长列表位置，确认挂载数有界、可见区域存在内容；20 次详情往返保持位置，320/390/430px 改变尺寸后继续正常渲染。
- 既有回归：刷新失败保留内容、第一页重试、配置选中频道、迟到分页隔离、六档宽度的媒体布局、瀑布流、互动、设置、新闻、图片缓存，以及播放器 renderjs 生命周期。
- 核心模块：原生索引读取/异步批量写入、下载并发/优先级/共享/取消、版本隔离、清理竞争；刷新 TTL/单飞/在途失效/滚动与后台暂停；帖子持有与作者同步。
- Android：已在当前连接的 Android 13 / WebView 101 / HBuilderX 5.15 调试基座上执行连续 20 次底栏切换、20 次详情往返（其中 6 次视频详情）。真实本地数据量为 7 条。该设备仅作为兼容和功能样本，不提供机型专用分支。
- 真机数据使用 CDP rAF 间隔和 Performance 指标，不能替代系统帧时间或正式包性能验收。原始报告包括长帧、位置恢复耗时、GC 后节点/监听器/堆大小，遇到空列表或后端不可达会失败，不把空页面视为流畅度通过。

最后一轮真机采样：109 个 rAF 间隔，P95 为 16.71ms，超过 50ms 的间隔为 0；20 次切页的位置恢复观测 P95 为 152ms（包含 CDP 与 20ms 轮询误差），尚不能据此认定正式包已达到 150ms 目标。先访问关注页再采样，20 次切页和详情往返前后的 GC 后监听器均为 329，节点为 789→781，未见随往返次数保留页面节点；JS 堆约 4.66→4.94MB。它们只覆盖本次样本，不代表所有设备及整个 APP 的内存上限。

低、中、高性能设备的正式包、多 WebView 版本及高刷新率验收仍待补充。目标为 60Hz 下接近 60fps、点击反馈 P95 ≤100ms、已访问底栏切换 P95 ≤150ms；目前不宣称这些目标已在全部安卓机型上达到。

## 复现与产物

前提：本地 H5 开发服务运行，Chromium 可用，设置 `UI_BROWSER_PATH`（Windows 本机 Chrome 路径）。本地构建使用 `--mode development`，不修改生产 HTTPS 检查或生产 API 地址。

```text
npm run typecheck
npm run check:performance-core
npm run check:performance
npm run check:feed-window
npm run check:home-refresh
npm run check:waterfall
npm run check:interactions
node scripts/verify-media-cache.cjs
npm run check:video
npm run check:scroll-config
npm run build:app -- --mode development
npm run build:h5 -- --mode development
npm run build:mp-weixin -- --mode development
```

单列压力测试：设置 `PERF_LAYOUT=single` 后执行 `npm run check:performance`。

真机：先通过 HBuilderX 同步源码并等待编译、同步完成；本地后端可用时执行 `npm run connect:usb`。CDP 使用 `scripts/android-cdp.cjs` 的 9224 端口，按实际 WebView socket 建立 ADB 转发后执行 `npm run check:android-performance`。脚本仅滚动、切页和返回，不点赞、关注、发布或提交评论；测试要求手机已解锁且项目首页处于前台。

本次真机验证使用过临时 `.env.development.local` USB 地址覆盖；验证结束删除该临时文件，保留原有 `.env.development`。原有开发配置指向的局域网曾出现断连，该网络问题与性能验收分开记录。

产物在 `artifacts/performance/`：`baseline.json`、`after.json`、`after-single.json`、`window-regression.json`、`android-before.json`、`android-after.json`、背景截图和优化前源码快照。`android-before.json` 只有旧版本的真实页面快照，不与 300 条浏览器样本做跨环境对比。

原有 `check:ui` 仍按早期「禁止业务图片和渐变」规范扫描整个项目。优化前后均失败 60 项，新增失败为 0；没有放宽该检查。比对记录为 `legacy-ui-audit.json`。新闻和设置回归分别需要指定有效的 `UI_ENTRY_IMAGE` 测试图片和 `UI_BROWSER_PATH`。
