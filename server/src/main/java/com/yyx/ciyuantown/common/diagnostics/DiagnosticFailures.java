package com.yyx.ciyuantown.common.diagnostics;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/** Classify failures without exposing raw exceptions, credentials or connection URLs. */
final class DiagnosticFailures {
    private DiagnosticFailures() {}

    static String describe(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        String timeout = null;
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof com.yyx.ciyuantown.common.storage.FtpStorage.Failure) return cause.getMessage();
            if (cause instanceof com.yyx.ciyuantown.common.exception.BizException) {
                if ("资源存储配置不存在".equals(cause.getMessage())) return "当前启用的文件存储配置不存在";
                if ("资源存储配置解密失败".equals(cause.getMessage()))
                    return "文件存储配置无法解密；检查 MEDIA_CONFIG_KEY（未配置时使用 JWT_SECRET）是否与保存配置时一致";
            }
            if (cause instanceof com.aliyun.oss.OSSException oss) {
                switch (oss.getErrorCode()) {
                    case "InvalidAccessKeyId", "SignatureDoesNotMatch": return "OSS 密钥无效或签名认证失败";
                    case "AccessDenied": return "OSS 账号没有访问 Bucket 的权限";
                    case "NoSuchBucket": return "OSS Bucket 不存在";
                    case "InvalidBucketName": return "OSS Bucket 名称不合法";
                    default: break;
                }
            }
            if (cause instanceof com.qiniu.common.QiniuException qiniu) {
                switch (qiniu.code()) {
                    case 401: return "七牛云认证失败，请检查密钥";
                    case 403: return "七牛云账号没有访问 Bucket 的权限";
                    case 612, 631: return "七牛云资源或 Bucket 不存在";
                    default: break;
                }
            }
            if (cause instanceof SQLException sql) {
                switch (sql.getErrorCode()) {
                    case 1045: return "数据库账号或密码错误，或账号不允许从当前服务器登录";
                    case 1044, 1142, 1143: return "数据库账号缺少访问权限";
                    case 1049: return "配置的数据库不存在";
                    case 1146: return "数据库缺少业务表，请检查是否已导入完整 SQL";
                    case 1054: return "数据库缺少业务字段，请检查数据库升级脚本";
                    default: break;
                }
            }
            if (cause instanceof UnknownHostException) return "域名无法解析";
            if (cause instanceof ConnectException) return "连接被拒绝或服务未监听，请检查服务、端口和防火墙";
            if (cause instanceof SocketTimeoutException || cause instanceof java.util.concurrent.TimeoutException)
                timeout = "连接或响应超时";
            if (cause instanceof AccessDeniedException) return "运行后端的账号没有目录读写权限";
            if (cause instanceof jakarta.mail.AuthenticationFailedException) return "SMTP 认证失败，请检查邮箱账号和授权码";
            if (cause instanceof javax.net.ssl.SSLException) return "TLS/SSL 握手失败，请检查证书和加密连接配置";
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("wrongpass") || message.contains("noauth")) return "Redis 认证失败，请检查密码和 ACL 配置";
            if (message.contains("noperm")) return "Redis 账号缺少命令权限";
            if (message.contains("timeout") || message.contains("timed out")) timeout = "连接或响应超时";
        }
        if (timeout != null) return timeout;
        return "检测失败，请检查配置和后端日志（异常类型：" + failure.getClass().getSimpleName() + "）";
    }
}
