package com.yyx.ciyuantown.common.diagnostics;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSSClientBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Region;
import com.qiniu.util.Auth;
import com.yyx.ciyuantown.common.storage.StorageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class DependencyChecks {
    private final JdbcTemplate db;
    private final RedisConnectionFactory redis;
    private final ObjectProvider<JavaMailSender> mail;
    private final StorageService storage;
    private final Environment env;

    public DependencyChecks(JdbcTemplate db, RedisConnectionFactory redis,
                            ObjectProvider<JavaMailSender> mail, StorageService storage, Environment env) {
        // Use a separate JdbcTemplate so diagnostic query timeouts do not affect business requests.
        this.db = new JdbcTemplate(Objects.requireNonNull(db.getDataSource()));
        this.db.setQueryTimeout(3);
        this.redis = redis;
        this.mail = mail;
        this.storage = storage;
        this.env = env;
    }

    @FunctionalInterface
    interface Probe { Outcome run() throws Exception; }
    record Check(String id, String name, String suggestion, Probe probe) {}
    record Outcome(String status, String message, String suggestion) {
        static Outcome up(String message) { return new Outcome("UP", message, "无需处理"); }
        static Outcome down(String message, String suggestion) { return new Outcome("DOWN", message, suggestion); }
        static Outcome warning(String message, String suggestion) { return new Outcome("WARN", message, suggestion); }
        static Outcome skipped(String message) { return new Outcome("SKIPPED", message, "无需处理"); }
    }

    List<Check> checks() {
        return List.of(
                new Check("mysql", "MySQL 数据库连接", "检查 DB_URL、DB_USERNAME、DB_PASSWORD，数据库服务、端口、防火墙和账号授权", this::mysql),
                new Check("schema", "数据库表与字段", "核对当前数据库，导入完整 SQL 并按顺序执行 doc/sql 中的升级脚本", this::schema),
                new Check("redis", "Redis 连接", "检查 REDIS_HOST、REDIS_PORT、REDIS_PASSWORD、REDIS_DATABASE，以及服务和 ACL 权限", this::redis),
                new Check("smtp", "SMTP 邮件连接", "检查 SMTP_HOST、SMTP_PORT、SMTP_USERNAME、SMTP_PASSWORD，邮箱 SMTP 开关、授权码及服务器出站端口", this::smtp),
                new Check("storage", "当前文件存储", "检查 UPLOAD_DIR 或当前存储配置；FTP 核对主机、端口、账号和根目录，云存储核对 Endpoint、地域、Bucket 和密钥；确认数据库存储配置可以读取", this::storage),
                new Check("media-domain", "媒体访问域名", "检查当前存储配置的访问域名、DNS、证书及 CDN 配置", this::mediaDomain),
                new Check("ffmpeg", "FFmpeg / FFprobe", "安装 FFmpeg 与 FFprobe，配置 FFMPEG_PATH 和 FFPROBE_PATH 的绝对路径，并授予执行权限", this::ffmpeg)
        );
    }

    Outcome mysql() {
        Integer value = db.queryForObject("SELECT 1", Integer.class);
        return Integer.valueOf(1).equals(value) ? Outcome.up("数据库连接和 SELECT 1 查询成功")
                : Outcome.down("数据库测试查询返回异常结果", "检查数据库服务和连接配置");
    }

    Outcome schema() {
        Map<String, Set<String>> expected = new TreeMap<>();
        // Derive the required schema from the same MyBatis mappings used by business requests.
        for (var info : TableInfoHelper.getTableInfos()) {
            Set<String> columns = new TreeSet<>();
            if (info.getKeyColumn() != null) columns.add(identifier(info.getKeyColumn()));
            for (var field : info.getFieldList()) columns.add(identifier(field.getColumn()));
            expected.put(identifier(info.getTableName()), columns);
        }
        expected.put("media_storage_config", Set.of("id", "config_json"));
        expected.put("media_object", Set.of("id", "storage_id", "object_key", "public_url", "content_type", "size_bytes"));
        expected.put("media_job", Set.of("asset_id", "config_json", "state", "attempts", "lease_until", "worker_token", "error_message", "create_time", "update_time"));
        Map<String, Set<String>> actual = new HashMap<>();
        db.query("SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> actual.computeIfAbsent(identifier(rs.getString(1)),
                        ignored -> new HashSet<>()).add(identifier(rs.getString(2))));
        List<String> missingTables = new ArrayList<>(), missingColumns = new ArrayList<>();
        for (var entry : expected.entrySet()) {
            var columns = actual.get(entry.getKey());
            if (columns == null) missingTables.add(entry.getKey());
            else for (String column : entry.getValue())
                if (!columns.contains(column)) missingColumns.add(entry.getKey() + "." + column);
        }
        if (!missingTables.isEmpty() || !missingColumns.isEmpty()) {
            List<String> problems = new ArrayList<>();
            if (!missingTables.isEmpty()) problems.add("缺少表：" + String.join("、", missingTables));
            if (!missingColumns.isEmpty()) problems.add("缺少字段：" + String.join("、", missingColumns));
            return Outcome.down(String.join("；", problems), "核对 DB_URL 中的数据库名；导入完整 SQL 并执行 doc/sql 中尚未执行的升级脚本");
        }
        return Outcome.up("已核对 " + expected.size() + " 张业务表，所需表和字段均存在");
    }

    Outcome redis() {
        if (!env.getProperty("ciyuantown.redis.enabled", Boolean.class, true))
            return Outcome.skipped("REDIS_ENABLED=false，Redis 功能已关闭，未尝试连接");
        try (var connection = redis.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping()) ? Outcome.up("Redis 认证和 PING 成功；未修改缓存数据")
                    : Outcome.down("Redis PING 未返回 PONG", "检查 Redis 服务和命令权限");
        }
    }

    Outcome smtp() throws Exception {
        var sender = mail.getIfAvailable();
        if (!(sender instanceof JavaMailSenderImpl configured))
            return Outcome.down("没有可检测的 SMTP 邮件发送器", "检查 Spring 邮件配置");
        if (configured.getUsername() == null || configured.getUsername().isBlank()
                || configured.getPassword() == null || configured.getPassword().isBlank())
            return Outcome.down("SMTP 账号或授权码未配置，验证码邮件无法发送", "配置 SMTP_USERNAME 和 SMTP_PASSWORD；QQ 邮箱需要 SMTP 授权码");
        // Copy effective TLS/auth settings; never change the live mail sender or send a message.
        var probe = new JavaMailSenderImpl();
        probe.setHost(configured.getHost());
        probe.setPort(configured.getPort());
        probe.setProtocol(configured.getProtocol());
        probe.setUsername(configured.getUsername());
        probe.setPassword(configured.getPassword());
        var properties = new Properties();
        properties.putAll(configured.getSession().getProperties());
        String prefix = "mail." + configured.getProtocol() + ".";
        properties.setProperty(prefix + "connectiontimeout", "3000");
        properties.setProperty(prefix + "timeout", "3000");
        properties.setProperty(prefix + "writetimeout", "3000");
        probe.setJavaMailProperties(properties);
        probe.testConnection();
        return Outcome.up("SMTP 连接、TLS 和认证检测成功；未发送邮件（不代表收件箱投递成功）");
    }

    Outcome storage() throws Exception {
        StorageService.Config config;
        try { config = storage.active(); }
        catch (org.springframework.dao.DataAccessException error) {
            return Outcome.down("无法从数据库读取当前存储配置：" + DiagnosticFailures.describe(error), "先处理 MySQL 连接、权限或缺少存储配置表的问题，再重新检测文件存储");
        }
        if ("local".equals(config.provider())) {
            if (config.localDir() == null || config.localDir().isBlank())
                return Outcome.down("本地存储目录未配置", "检查 UPLOAD_DIR 和当前存储配置");
            Path root = Path.of(config.localDir()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Path file = Files.createTempFile(root, ".diagnostics-", ".tmp");
            try {
                Files.writeString(file, "ciyuantown diagnostics");
                if (!"ciyuantown diagnostics".equals(Files.readString(file)))
                    return Outcome.down("本地文件读写校验失败", "检查存储磁盘和目录权限");
            } finally {
                Files.deleteIfExists(file);
            }
            return Outcome.up("本地存储目录创建、写入、读取、删除成功；临时检测文件已清理");
        }
        if ("ftp".equals(config.provider())) {
            storage.checkFtp(config);
            return Outcome.up("FTP 连接、认证与根目录访问成功；未写入文件，上传与公开访问需在存储配置中检测");
        }
        if (blank(config.bucket()) || blank(config.accessKey()) || blank(config.secretKey()))
            return Outcome.down("云存储 Bucket 或密钥未配置", "检查当前存储配置的 Bucket、AccessKey 和 SecretKey");
        if ("oss".equals(config.provider())) {
            if (blank(config.endpoint())) return Outcome.down("OSS Endpoint 未配置", "配置完整 OSS Endpoint（包含 https://）");
            var options = new ClientBuilderConfiguration();
            options.setConnectionTimeout(3000);
            options.setSocketTimeout(3000);
            options.setConnectionRequestTimeout(3000);
            options.setMaxErrorRetry(0);
            options.setRequestTimeoutEnabled(true);
            options.setRequestTimeout(5000);
            var client = new OSSClientBuilder().build(config.endpoint(), config.accessKey(), config.secretKey(), options);
            try {
                if (!client.doesBucketExist(config.bucket()))
                    return Outcome.down("OSS Bucket 不存在或当前账号无法访问", "核对 Endpoint、Bucket 和账号权限");
            } finally { client.shutdown(); }
            return Outcome.up("阿里云 OSS 连接、认证与 Bucket 查询成功；未写入对象，上传权限需实际上传验证");
        }
        if ("qiniu".equals(config.provider())) {
            var region = switch (Objects.requireNonNullElse(config.region(), "")) {
                case "z0" -> Region.region0(); case "z1" -> Region.region1(); case "z2" -> Region.region2();
                case "na0" -> Region.regionNa0(); case "as0" -> Region.regionAs0(); default -> Region.autoRegion();
            };
            var options = new com.qiniu.storage.Configuration(region);
            options.useHttpsDomains = true;
            options.connectTimeout = 3;
            options.readTimeout = 3;
            options.writeTimeout = 3;
            new BucketManager(Auth.create(config.accessKey(), config.secretKey()), options)
                    .listFiles(config.bucket(), "", null, 1, null);
            return Outcome.up("七牛云连接、认证与 Bucket 文件列表查询成功；未写入对象，上传权限需实际上传验证");
        }
        return Outcome.down("当前存储类型不受支持", "将存储类型配置为 local、oss、qiniu 或 ftp");
    }

    Outcome mediaDomain() throws Exception {
        StorageService.Config config;
        try { config = storage.active(); }
        catch (org.springframework.dao.DataAccessException error) {
            return Outcome.down("无法从数据库读取媒体域名配置：" + DiagnosticFailures.describe(error), "先处理 MySQL 连接、权限或存储配置表的问题，再重新检测媒体域名");
        }
        if (blank(config.domain())) {
            return "local".equals(config.provider()) ? Outcome.skipped("本地存储使用后端 /uploads 地址，未配置独立域名")
                    : Outcome.down("远程存储公开访问域名未配置", "配置可公开读取的 HTTP/HTTPS 媒体域名");
        }
        URI uri = URI.create(config.domain());
        if (!Set.of("http", "https").contains(Objects.requireNonNullElse(uri.getScheme(), "")) || uri.getHost() == null)
            return Outcome.down("媒体域名格式错误", "配置完整的 http:// 或 https:// 地址");
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("HEAD");
        try {
            int status = connection.getResponseCode();
            if (status >= 200 && status < 400)
                return Outcome.up("媒体域名 DNS、HTTP/TLS 可达（HTTP " + status + "）；实际文件和重定向后的访问需另行验证");
            if (status >= 400 && status < 500)
                return Outcome.warning("媒体域名已连接，但根地址返回 HTTP " + status + "；根地址不一定允许访问",
                        "用真实图片或视频地址确认公开读取权限，必要时检查 CDN 与域名配置");
            return Outcome.down("媒体域名返回 HTTP " + status, "检查源站服务和 CDN 回源配置");
        } finally { connection.disconnect(); }
    }

    Outcome ffmpeg() throws Exception {
        String ffmpeg = env.getProperty("ciyuantown.ffmpeg.path", "");
        String ffprobe = env.getProperty("ciyuantown.ffmpeg.ffprobe-path", "");
        List<String> missing = new ArrayList<>();
        if (!executable(ffmpeg)) missing.add("FFMPEG_PATH");
        if (!executable(ffprobe)) missing.add("FFPROBE_PATH");
        if (!missing.isEmpty()) return Outcome.down("未配置有效的可执行文件：" + String.join("、", missing),
                "安装 FFmpeg/FFprobe 并配置绝对路径；Linux 下确保运行账号拥有执行权限");
        if (!run(List.of(ffmpeg, "-version"))) return Outcome.down("FFmpeg 版本命令执行失败", "检查 FFMPEG_PATH 指向的文件及运行依赖");
        if (!run(List.of(ffprobe, "-version"))) return Outcome.down("FFprobe 版本命令执行失败", "检查 FFPROBE_PATH 指向的文件及运行依赖");
        if (!run(List.of(ffmpeg, "-hide_banner", "-filters"))) return Outcome.down("FFmpeg 滤镜命令执行失败", "检查 FFmpeg 安装及滤镜支持");
        String font = env.getProperty("ciyuantown.ffmpeg.font-path", "");
        if (blank(font) || !Files.isRegularFile(Path.of(font)) || !Files.isReadable(Path.of(font)))
            return Outcome.warning("FFmpeg、FFprobe 与滤镜命令可运行；水印字体未配置或不可读", "配置 FFMPEG_FONT_PATH，供文字水印使用");
        return Outcome.up("FFmpeg、FFprobe 与滤镜命令可运行，水印字体可读取；未执行实际转码");
    }

    private static boolean run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw new java.util.concurrent.TimeoutException();
            return process.exitValue() == 0;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static boolean executable(String value) {
        return !blank(value) && Files.isRegularFile(Path.of(value)) && Files.isExecutable(Path.of(value));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String identifier(String value) { return value.replace("`", "").toLowerCase(Locale.ROOT); }
}
