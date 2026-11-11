# 次元町 · 客户端（uni-app x）

uni-app x 客户端源码，一套代码构建 Android、iOS、H5 与微信小程序。Android 安装包见 [Releases](https://github.com/TravelerYYX/cytown/releases/latest)。

工程使用 HBuilderX 5.26.2026091802 正式版、App 蒸汽模式、`App.uvue`、`main.uts`、UVUE 页面与 UTS 业务模块，包含 39 个页面、84 个组件。

构建、USB 调试与签名步骤见 [构建说明](docs/uniapp-x-build-and-usb.md)。

```powershell
npm ci --ignore-scripts
npm run check:architecture
npm run check:request-recovery
npm run check:performance-core
npm run check:native-core
npm run check:native-scroll
npm run check:native-runtime
npm run check:video
npm run dev:h5 -- --port 15273 --host 127.0.0.1
```

HBuilderX 位于其他目录时设置 `HBUILDERX_HOME`。编译和类型检查使用该版本自带编译器；npm 中不再安装旧 uni-app-plus 运行依赖。

首页、关注和「我的」单列使用 App 原生复用列表，双列沿用原列分配与窗口渲染；播放器菜单、发布工具和版区选择使用受高度约束的原生滚动容器。
