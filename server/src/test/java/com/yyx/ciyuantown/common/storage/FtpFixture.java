package com.yyx.ciyuantown.common.storage;

import com.sun.net.httpserver.HttpServer;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.*;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A loopback-only, disposable real FTP server and matching public HTTP origin. */
public final class FtpFixture implements AutoCloseable {
    public final Path home;
    public final Path media;
    public final int port;
    public final String domain;
    public volatile boolean denyDelete, denyWrites, denyRename, interruptUpload;
    public final AtomicInteger writes = new AtomicInteger();
    private final FtpServer ftp;
    private final HttpServer http;

    public FtpFixture(Path directory) throws Exception {
        home = Files.createDirectories(directory.resolve("home"));
        media = Files.createDirectories(home.resolve("media"));
        var factory = new FtpServerFactory();
        var users = new PropertiesUserManagerFactory();
        users.setFile(Files.createFile(directory.resolve("users.properties")).toFile());
        users.setPasswordEncryptor(new ClearTextPasswordEncryptor());
        var manager = users.createUserManager();
        BaseUser user = new BaseUser();
        user.setName("fixture");
        user.setPassword(" secret with spaces");
        user.setHomeDirectory(home.toString());
        user.setAuthorities(List.of(new WritePermission()));
        manager.save(user);
        factory.setUserManager(manager);
        factory.setFtplets(new HashMap<>(Map.of("failures", new DefaultFtplet() {
            @Override public FtpletResult beforeCommand(FtpSession session, FtpRequest request) throws FtpException, IOException {
                String command = request.getCommand();
                if (List.of("STOR", "MKD", "DELE", "RNFR", "RNTO").contains(command)) writes.incrementAndGet();
                if ((denyDelete && command.equals("DELE"))
                        || (denyWrites && List.of("STOR", "MKD", "RNFR", "RNTO").contains(command))
                        || (denyRename && command.equals("RNTO"))) {
                    session.write(new DefaultFtpReply(550, "denied; credential=must-not-leak"));
                    return FtpletResult.SKIP;
                }
                if (interruptUpload && command.equals("STOR")) {
                    Path parent = home.resolve(session.getFileSystemView().getWorkingDirectory().getAbsolutePath().substring(1));
                    Files.writeString(parent.resolve(request.getArgument()), "partial transfer");
                    return FtpletResult.DISCONNECT;
                }
                return FtpletResult.DEFAULT;
            }
        })));
        var listener = new ListenerFactory();
        listener.setServerAddress("127.0.0.1");
        listener.setPort(0);
        factory.addListener("default", listener.createListener());
        ftp = factory.createServer();
        ftp.start();
        port = factory.getListener("default").getPort();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/files/", exchange -> {
            Path file = media.resolve(exchange.getRequestURI().getPath().substring("/files/".length())).normalize();
            try {
                if (!file.startsWith(media) || !Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    byte[] bytes = Files.readAllBytes(file);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } finally { exchange.close(); }
        });
        http.start();
        domain = "http://127.0.0.1:" + http.getAddress().getPort() + "/files";
    }

    public StorageService.Config config() {
        return new StorageService.Config("ftp-fixture", "ftp", "", "", "", "", "", "", domain,
                "127.0.0.1", port, "fixture", " secret with spaces", "/media");
    }

    @Override public void close() { http.stop(0); ftp.stop(); }
}
