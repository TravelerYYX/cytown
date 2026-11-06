# APP / H5 瀑布流与竖屏视频封面修复

后端 `FfmpegService.frame` 原先把自动封面强制缩放并裁切到 16:9，竖屏视频上下内容已在文件生成时丢失。封面编辑页和后端裁剪接口同样固定 16:9。前端缓存图片依赖事件透传、瀑布流依赖异步原生测量，会受到两端加载与渲染时机影响。

## 修改

- 自动选帧和指定时间选帧均保留完整画面，按最大边压缩，禁止放大；保留视频旋转信息对应的显示方向。
- 封面编辑页按视频原比例展示、选择时间、保存完整画面；后端裁剪接口校验保留视频原比例。
- `CachedImage` 显式传递 `mode`、加载和最终失败事件，缓存文件加载失败后先尝试原地址。
- 瀑布流优先使用媒体原比例。分列使用统一 rpx 尺寸估算，不再依赖 APP 原生桥接的测量时机；刷新、互动与分页保持已有帖子分列。

## 已有数据和调试基座

运行 `scripts/repair-video-covers.py` 修复本地自动封面。素材 25、29 的旧封面均为 720×404，现恢复为 720×1280、720×960。新封面使用新 URL 和资源版本；原文件保留。回滚 SQL 与清单位于 `ciyuantoen-back/ciyuantown-back/backups/video-covers-20261011-restored/`。脚本默认为预览，应用需显式指定 `--apply --backup`，重复预览不会再次修复已正确的封面。

APP 新构建已同步到连接的 HBuilder 调试基座。原 JS 和 CSS 备份位于前端 `artifacts/waterfall/android-before-cover-fix/`；原首页和封面页 CSS 另存为该目录的 `index.css`、`cover.css`。

本地后端重启命令被自动审批策略拒绝，当前 IntelliJ 后端进程未停止。需在 IDE 重启后端，后续新上传视频才能使用修改后的生成逻辑。

## 验证

- `npm run typecheck`、`npm run check:waterfall`、`npm run check:publish` 通过。
- APP、H5 开发模式构建通过。
- `VideoCoverTests` 两项通过，覆盖横屏、3:4、9:16、小尺寸不放大、完整画面上下色块和旋转视频。
- 在独立临时 MySQL 中运行 `MediaIntegrationTests#selectedPortraitCoverPreservesOriginalRatio` 通过，验证保存完整竖屏封面、更新版本以及拒绝旧横向裁切；临时数据库和进程已清理。
- `node scripts/verify-waterfall-platforms.cjs` 对比连接的真实 APP 与 H5 同批帖子：分列和封面比例一致，竖屏封面文件与容器均为 3:4。
- 截图和实测数据位于前端 `artifacts/waterfall/`：`android-full-video-cover.png`、`h5-full-video-cover.png`、`portrait-cover-restored.png`、`app-h5-cover-comparison.json`。
