# 次元町 · uni-app x 迁移中

当前目标是完整迁移 Android、iOS、H5、微信客户端。**整体尚未完成，不能作为已验收的替换版本交付。**

工程使用 HBuilderX 5.26.2026091802 正式版、App 蒸汽模式、App.uvue、main.uts、UVUE 页面与 UTS 业务模块。原 20 页面、38 组件及 App 均有新源码；原 Vue 备份位于客户端运行目录外。浏览器播放器只进入 H5，web-view 仅保留原外链页面。

详细构建、USB 调试、签名与证据口径见 [构建说明](docs/uniapp-x-build-and-usb.md)。逐文件状态见 [迁移矩阵](../deliverables/uniapp-x-migration/migration-matrix.md)，历史演示项见 [历史缺口](../deliverables/uniapp-x-migration/historical-gaps.md)。

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

HBuilderX 位于其他目录时设置 HBUILDERX_HOME。编译和类型检查使用该版本自带编译器；npm 中不再安装旧 uni-app-plus 运行依赖。

当前页面仍保留真实接口、字段及业务规则；原有聊天会话、角色卡演示、待上线提示和空操作导航单独记录，不计作真实业务通过。H5 的真实数据只读检查与原生控制器单测不能替代真机全功能验收。

首页、关注和“我的”单列已接入 App 原生复用列表；双列继续使用原列分配及窗口渲染。播放器菜单、发布工具和版区选择使用受高度约束的原生滚动容器。用户已确认所有纯视觉细节以原生体感为准，具体差异见 [兼容决策](../deliverables/uniapp-x-migration/compatibility-decisions.md)。

已按模拟器日志处理原生 button 子节点、动态 CSS 长度、滚动方向、SVG 解码、文件大小查询和调试隧道；新增 ActionButton、原生无障碍适配及对应回归检查。原始日志、截图和无障碍节点证据见工程外 emulator-debug/；当前运行结果以 regression-report.json 为准，资源构建不能替代原生运行验收。

仍需验证原生布局、正文混排、播放器四端行为和完整交互。微信已接入原生视频全屏及 cover-view 自绘控制层，覆盖层显示、触摸和实际倍速支持仍待微信环境验证。当前可使用 Android 模拟器；USB 手机、测试账号、正式安装包和正式包性能仍待验收。
