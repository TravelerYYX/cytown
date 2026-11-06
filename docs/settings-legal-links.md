# 设置页协议链接

后台「全局设置 → 设置页协议链接」分别配置用户协议、隐私政策、个人信息收集清单：

- 链接：HTTP/HTTPS 完整地址，最长 2048 字符，可留空。
- 打开方式：默认「外部浏览器打开」，也可选择「APP 内打开」。APP 内使用现有网页页，显示标题与返回按钮。
- 点击「保存协议链接」保存。APP 每次进入设置页会重新读取配置，无需重新发版。
- 未配置时提示「暂未配置链接」；读取失败时点击入口可重试。

接口：后台 `GET/POST /global-settings/legal-links`，沿用管理员鉴权及只读账号限制；APP 公开只读接口 `GET /app/legal-links`，未登录也可读取。

配置存储在已有 `system_config` 表的 `settings_legal_links` 键，无需新增数据库表或执行迁移。与资源删除开关独立保存。

```json
{
  "userAgreement": { "linkUrl": "", "openMode": "browser" },
  "privacyPolicy": { "linkUrl": "", "openMode": "browser" },
  "personalInfoCollection": { "linkUrl": "", "openMode": "browser" }
}
```

`openMode` 仅允许 `browser` / `inapp`。本次仅调整上述三个入口，第三方共享个人信息清单沿用原有行为。
