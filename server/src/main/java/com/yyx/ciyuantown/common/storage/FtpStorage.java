package com.yyx.ciyuantown.common.storage;

import com.yyx.ciyuantown.common.exception.BizException;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

/** FTP paths are relative to the configured root in the account's virtual filesystem. */
public final class FtpStorage {
    private FtpStorage() {}

    /** Only fixed operation names and numeric replies are exposed, never server reply text. */
    public static final class Failure extends IOException {
        private Failure(String operation, FTPClient client) {
            super(operation + "（FTP 响应码 " + client.getReplyCode() + "）");
        }
    }

    public static void validate(StorageService.Config c) {
        try {
            if (c.ftpHost().isBlank() || c.ftpHost().chars().anyMatch(Character::isWhitespace)
                    || c.ftpHost().contains("/") || c.ftpHost().contains("\\")
                    || new URI("ftp", null, c.ftpHost(), c.ftpPort(), null, null, null).getHost() == null)
                throw new IllegalArgumentException();
        } catch (Exception e) { throw new BizException("FTP 主机需填写主机名或 IP，不含协议、端口和路径"); }
        if (c.ftpPort() < 1 || c.ftpPort() > 65535) throw new BizException("FTP 端口需在 1 至 65535 之间");
        if (c.ftpUsername().isBlank() || c.ftpPassword().isEmpty()) throw new BizException("请填写 FTP 用户名和密码");
        if (controls(c.ftpUsername()) || controls(c.ftpPassword())) throw new BizException("FTP 账号密码不能包含控制字符");
        String root = c.ftpRootDir();
        if (!root.startsWith("/") || root.contains("\\") || controls(root)
                || Arrays.stream(root.split("/")).anyMatch(s -> s.equals("..") || s.equals(".")))
            throw new BizException("FTP 根目录需为登录后的绝对路径，不能包含 . 或 ..");
    }

    public static void check(StorageService.Config c) throws IOException {
        try (var session = connect(c)) { /* Login and CWD only: diagnostic probes never write. */ }
    }

    public static void upload(StorageService.Config c, String key, Path source) throws IOException {
        checkKey(key);
        try (var session = connect(c)) {
            FTPClient client = session.client;
            String name = enterParent(client, key, true);
            String part = "." + name + "." + UUID.randomUUID() + ".part";
            try {
                try (var input = Files.newInputStream(source)) {
                    require(client.storeFile(part, input), "FTP 文件上传失败", client);
                }
                require(client.rename(part, name), "FTP 文件发布失败", client);
            } catch (IOException | RuntimeException failure) {
                // Reconnect: a failed data transfer may leave the control channel out of sync.
                String parent = key.contains("/") ? key.substring(0, key.lastIndexOf('/') + 1) : "";
                try { delete(c, parent + part); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    public static void download(StorageService.Config c, String key, Path target) throws IOException {
        checkKey(key);
        Path part = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try (var session = connect(c)) {
            String name = enterParent(session.client, key, false);
            try (var output = Files.newOutputStream(part)) {
                require(session.client.retrieveFile(name, output), "FTP 文件读取失败", session.client);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(part); }
    }

    public static void delete(StorageService.Config c, String key) throws IOException {
        checkKey(key);
        try (var session = connect(c)) {
            FTPClient client = session.client;
            String name = enterParent(client, key, false);
            if (!client.deleteFile(name)) {
                // 550 may mean denied access. Only treat absence as success after a successful listing.
                var files = client.listFiles();
                require(FTPReply.isPositiveCompletion(client.getReplyCode())
                        && Arrays.stream(files).noneMatch(file -> name.equals(file.getName())), "FTP 文件删除失败", client);
            }
        }
    }

    private static Session connect(StorageService.Config c) throws IOException {
        validate(c);
        FTPClient client = new FTPClient();
        client.setControlEncoding("UTF-8");
        client.setConnectTimeout(15_000);
        client.setDefaultTimeout(60_000);
        client.setDataTimeout(Duration.ofSeconds(60));
        client.setListHiddenFiles(true);
        client.setControlKeepAliveTimeout(Duration.ofSeconds(30));
        Session session = new Session(client);
        try {
            client.connect(c.ftpHost(), c.ftpPort());
            require(FTPReply.isPositiveCompletion(client.getReplyCode()), "FTP 服务器拒绝连接", client);
            client.setSoTimeout(60_000);
            require(client.login(c.ftpUsername(), c.ftpPassword()), "FTP 登录认证失败", client);
            client.enterLocalPassiveMode();
            require(client.setFileType(FTP.BINARY_FILE_TYPE), "FTP 二进制模式设置失败", client);
            require(client.changeWorkingDirectory(c.ftpRootDir()), "FTP 根目录不存在或没有访问权限", client);
            return session;
        } catch (IOException | RuntimeException failure) {
            session.close();
            throw failure;
        }
    }

    private static String enterParent(FTPClient client, String key, boolean create) throws IOException {
        String[] parts = key.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (!client.changeWorkingDirectory(parts[i])) {
                if (create) {
                    // Another concurrent upload may have created the directory already.
                    client.makeDirectory(parts[i]);
                    require(client.changeWorkingDirectory(parts[i]), "FTP 子目录创建或访问失败", client);
                } else throw new Failure("FTP 子目录不存在或没有访问权限", client);
            }
        }
        return parts[parts.length - 1];
    }

    private static void checkKey(String key) {
        StorageService.checkKey(key);
        if (controls(key) || Arrays.stream(key.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".")))
            throw new BizException("资源路径不合法");
    }

    private static boolean controls(String value) { return value.chars().anyMatch(Character::isISOControl); }
    private static void require(boolean success, String operation, FTPClient client) throws Failure {
        if (!success) throw new Failure(operation, client);
    }

    private static final class Session implements AutoCloseable {
        private final FTPClient client;
        private Session(FTPClient client) { this.client = client; }
        @Override public void close() {
            // Disconnect also after authentication/transfer failures; never mask the primary error.
            if (client.isConnected()) try { client.disconnect(); } catch (IOException ignored) { }
        }
    }
}
