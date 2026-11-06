# Spring Boot 后端连接检测

部署新 JAR 并重启后，在浏览器直接访问：

```text
http://服务器IP:8080/diagnostics
```

端口以 `SERVER_PORT` 为准，默认 8080。这个地址无需登录，返回中文 HTML 页面，不依赖管理后台或前端构建。通过 Nginx 或网关访问时，请把 `/diagnostics` 和 `/diagnostics.json` 转发到 Java 后端；如果网关给后端统一加了 `/api` 前缀，外部访问地址相应为 `/api/diagnostics`，网关应按已有规则去掉前缀。

页面逐项显示状态、原因、处理建议和耗时。异常项排在前面；未配置的邮件或 FFmpeg 会明确报异常，主动关闭的 Redis 显示“已跳过”。字体未配置以及媒体域名根路径返回 4xx 时显示“待确认”，不会误判为已经验证所有功能。检测并行执行，单次报告最多等待约 8 秒；某项卡住也会展示其他已完成的结果。结果在后端缓存 10 秒，请稍后再点击“重新检测”观察配置或服务恢复后的变化。

## 检测范围

| 项目 | 实际检测操作 | 验证范围 |
| --- | --- | --- |
| MySQL | 使用后端 DataSource 执行 `SELECT 1` | 连接、认证、查询；不修改业务数据 |
| 数据库结构 | 比较 MyBatis 映射及媒体表与当前库的 `information_schema.COLUMNS` | 明确列出缺失表、缺失字段；不自动导入 SQL，不检查完整约束、索引或所有业务 SQL |
| Redis | 使用后端 RedisConnectionFactory 执行 `PING` 并关闭连接 | 当前连接和认证；不写入缓存，不代表所有业务读写命令均有权限 |
| SMTP | 使用实际配置的协议、TLS、账号与授权码连接和认证 | 不发邮件，不验证收件箱投递结果 |
| 本地存储 | 在当前存储目录创建唯一临时文件，写入、读取并删除 | 目录及基本读写能力，保留已有文件 |
| OSS / 七牛 | 使用当前数据库中的存储配置，OSS 查询 Bucket，七牛列举最多一个文件 | 验证连接与查询权限，不上传、不删除云对象；不代表上传权限已验证 |
| 媒体访问域名 | 对当前存储配置的域名执行 HTTP HEAD | DNS、HTTP/TLS 可达性；不跟随重定向，不验证具体资源、CDN 内容或浏览器 CORS |
| FFmpeg / FFprobe | 检查文件和执行权限，执行版本命令及 FFmpeg 滤镜命令，检查水印字体可读性 | 每次实时检测，不执行转码；不代表编码器和完整转码流程已验证 |

文件存储与媒体域名需要先从 MySQL 读取当前配置，因此数据库故障也可能导致这两项检测失败。页面会说明实际故障分类，不输出连接字符串、账号、密码、存储密钥或完整异常原文。

## JSON 接口

```text
GET /diagnostics.json
```

返回 `status`（`UP` / `WARN` / `DOWN`）、UTC 格式的 `checkedAt`、`durationMs` 和 `checks`。每项包含 `id`、`name`、`status`（另有 `SKIPPED`）、`message`、`suggestion`、`durationMs`。

有 `DOWN` 时 JSON 返回 HTTP 503，否则返回 HTTP 200。HTML 页面始终返回 HTTP 200，以便反向代理保留中文诊断结果。两个接口均设置 `Cache-Control: no-store`，后端内部仍保留 10 秒检测结果。

## 页面打不开时

诊断接口只能在 Spring Boot 成功启动后访问。缺少必填环境变量、端口冲突或其他启动错误，需要先查看 Java 启动日志。服务器本机可以先测试：

```bash
curl http://127.0.0.1:8080/diagnostics
curl -i http://127.0.0.1:8080/diagnostics.json
```

本机能打开而外部打不开时，检查服务器安全组、防火墙、Nginx 转发、DNS 和 HTTPS 证书。通过浏览器打开此页面只说明这次请求已经到达后端，不代表 APP/H5 的域名、浏览器跨域配置或其他公网链路均已验证。

## 构建与验证

需要 Java 21：

```bash
./mvnw -Dtest=DiagnosticTests,DiagnosticWebTests test
./mvnw -DskipTests package
```

部署文件为 `target/ciyuantown-back-0.0.1-SNAPSHOT.jar`。已有 `.env` 和其他部署环境变量继续使用，无需新增诊断密钥。
