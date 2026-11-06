package com.yyx.ciyuantown.common.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FtpStorageTests {
    @TempDir Path root;
    FtpFixture server;
    StorageService storage;
    final ObjectMapper json = new ObjectMapper();
    final Map<String,String> configs = new HashMap<>();
    final Map<String,StorageService.Stored> objects = new HashMap<>();
    String active;
    boolean failRegistry;

    @BeforeEach void setup() throws Exception {
        server = new FtpFixture(root.resolve("server"));
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(contains("system_config"), eq(String.class))).thenAnswer(i -> active == null ? List.of() : List.of(active));
        when(db.queryForList(contains("media_storage_config"), eq(String.class), any())).thenAnswer(i ->
                configs.containsKey(i.getArgument(2)) ? List.of(configs.get(i.getArgument(2))) : List.of());
        when(db.update(anyString(), any(Object[].class))).thenAnswer(i -> {
            String sql = i.getArgument(0);
            if (sql.contains("INTO media_storage_config")) configs.putIfAbsent(i.getArgument(1), i.getArgument(2));
            else if (sql.contains("INTO system_config")) active = i.getArgument(1);
            else if (sql.contains("INTO media_object")) {
                if (failRegistry) throw new BadSqlGrammarException("insert", "", new SQLException("fixture secret", "42000", 1142));
                var o = new StorageService.Stored(i.getArgument(1), i.getArgument(2), i.getArgument(3), i.getArgument(4), i.getArgument(5), i.getArgument(6));
                objects.put(o.id(), o);
            } else if (sql.contains("DELETE FROM media_object")) objects.remove(i.getArgument(1));
            return 1;
        });
        when(db.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(i ->
                objects.values().stream().filter(o -> o.url().equals(i.getArgument(2))).toList());
        storage = new StorageService(db, json);
        ReflectionTestUtils.setField(storage, "uploadDir", root.resolve("local").toString());
        ReflectionTestUtils.setField(storage, "encryptionKey", "ftp-test-encryption-key");
    }

    @AfterEach void cleanup() { if (server != null) server.close(); }

    private StorageService.Config change(StorageService.Config c, String field, Object value) throws Exception {
        var tree = json.valueToTree(c);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).set(field, json.valueToTree(value));
        return json.treeToValue(tree, StorageService.Config.class);
    }

    private Path source() throws Exception {
        return Files.write(root.resolve("source.bin"), new byte[]{0, 13, 10, -1, 1, 2, 3, 0, 10});
    }

    @Test void binaryUnicodePathsPublicMappingHistoricalReadAndDelete() throws Exception {
        storage.save(server.config());
        var c = storage.active();
        Path source = source();
        var object = storage.put(c, "图片/720/片段 01.ts", source, "video/mp2t");
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(server.media.resolve(object.key())));
        assertTrue(object.url().startsWith(server.domain + "/%E5%9B%BE%E7%89%87/720/"));
        assertTrue(object.url().endsWith("%2001.ts"));
        try (var in = URI.create(object.url()).toURL().openStream()) { assertArrayEquals(Files.readAllBytes(source), in.readAllBytes()); }
        storage.save(new StorageService.Config("", "local", root.resolve("new-local").toString(), "", "", "", "", "", ""));
        assertEquals(c.id(), storage.find(object.url()).orElseThrow().storageId());
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(storage.materialize(object.url(), root.resolve("download"))));
        storage.delete(object.url());
        assertFalse(Files.exists(server.media.resolve(object.key())));
        assertTrue(storage.find(object.url()).isEmpty());
    }

    @Test void passwordsAreEncryptedMaskedAndRetainedWithoutTrimming() throws Exception {
        var masked = storage.save(server.config());
        assertEquals("", masked.ftpPassword());
        String encrypted = configs.get(masked.id());
        assertFalse(encrypted.contains("secret with spaces"));
        assertEquals(" secret with spaces", storage.active().ftpPassword());
        storage.test(masked);
        var next = storage.save(masked);
        assertNotEquals(masked.id(), next.id());
        assertEquals(" secret with spaces", storage.active().ftpPassword());
        assertEquals(" secret with spaces", storage.config(masked.id()).ftpPassword());
        storage.save(change(storage.active(), "ftpPassword", "  trailing spaces  "));
        assertEquals("  trailing spaces  ", storage.active().ftpPassword());
    }

    @Test void legacyJsonAndDefaultFtpFieldsRemainCompatible() throws Exception {
        configs.put("legacy", "{\"id\":\"legacy\",\"provider\":\"local\",\"localDir\":\"/uploads\",\"endpoint\":\"\",\"region\":\"\",\"bucket\":\"\",\"accessKey\":\"\",\"secretKey\":\"\",\"domain\":\"\"}");
        var legacy = storage.config("legacy");
        assertEquals("", legacy.ftpPassword());
        assertEquals(21, legacy.ftpPort());
        assertEquals("/", legacy.ftpRootDir());
        assertEquals(21, change(server.config(), "ftpPort", null).ftpPort());
        assertEquals("/", change(server.config(), "ftpRootDir", "").ftpRootDir());
    }

    @Test void passwordTrailingWhitespaceIsSentUnchanged() throws Exception {
        // Apache FtpServer trims incoming request lines; use a protocol peer to check the exact PASS bytes.
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var commands = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var socket = listener.accept();
                     var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                     var output = new java.io.PrintWriter(socket.getOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8)) {
                    socket.setSoTimeout(5000);
                    output.print("220 ready\r\n"); output.flush();
                    String passwordLine = null, line;
                    while ((line = input.readLine()) != null) {
                        if (line.startsWith("PASS ")) passwordLine = line;
                        output.print((line.startsWith("USER ") ? "331 password" : line.startsWith("PASS ") ? "230 logged in" : line.startsWith("CWD ") ? "250 directory" : "200 ok") + "\r\n");
                        output.flush();
                    }
                    return passwordLine;
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            var c = change(change(server.config(), "ftpPort", listener.getLocalPort()), "ftpPassword", " secret with spaces ");
            FtpStorage.check(c);
            assertEquals("PASS  secret with spaces ", commands.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void connectionTestChecksFtpAndHttpThenRemovesTheObject() throws Exception {
        storage.test(server.config());
        try (var files = Files.walk(server.media)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
        var badDomain = change(server.config(), "domain", server.domain + "/wrong-map");
        var failure = assertThrows(BizException.class, () -> storage.test(badDomain));
        assertTrue(failure.getMessage().contains("HTTP 公开读取"));
        assertTrue(failure.getMessage().contains("HTTP 404"));
        try (var files = Files.walk(server.media)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
    }

    @Test void failedDeletionCannotPassConnectionTestOrDeleteTheRegistry() throws Exception {
        var object = storage.put(server.config(), "posts/keep.bin", source(), "application/octet-stream");
        server.denyDelete = true;
        var error = assertThrows(BizException.class, () -> storage.test(server.config()));
        assertTrue(error.getMessage().contains("清理失败"));
        assertFalse(error.getMessage().contains("must-not-leak"));
        assertThrows(BizException.class, () -> storage.delete(object.url()));
        assertTrue(storage.find(object.url()).isPresent());
        assertTrue(Files.exists(server.media.resolve(object.key())));
    }

    @Test void registryFailureAndTransactionRollbackRemoveRemoteFiles() throws Exception {
        Path source = source();
        failRegistry = true;
        var error = assertThrows(BizException.class, () -> storage.put(server.config(), "posts/failed.bin", source, "application/octet-stream"));
        assertTrue(error.getMessage().contains("权限"));
        assertFalse(Files.exists(server.media.resolve("posts/failed.bin")));
        failRegistry = false;
        TransactionSynchronizationManager.initSynchronization();
        try {
            storage.put(server.config(), "posts/rollback.bin", source, "application/octet-stream");
            for (var callback : TransactionSynchronizationManager.getSynchronizations()) callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertFalse(Files.exists(server.media.resolve("posts/rollback.bin")));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void badAuthenticationAndRootAreRejectedWithoutCreatingDirectories() throws Exception {
        var wrongPassword = change(server.config(), "ftpPassword", "bad-secret");
        var error = assertThrows(FtpStorage.Failure.class, () -> FtpStorage.check(wrongPassword));
        assertTrue(error.getMessage().contains("认证失败"));
        assertFalse(error.getMessage().contains("bad-secret"));
        var detected = assertThrows(BizException.class, () -> storage.test(wrongPassword));
        assertTrue(detected.getMessage().contains("登录认证失败"));
        assertTrue(detected.getMessage().contains("530"));
        assertFalse(detected.getMessage().contains("bad-secret"));
        var missingRoot = change(server.config(), "ftpRootDir", "/not-created");
        assertThrows(FtpStorage.Failure.class, () -> FtpStorage.check(missingRoot));
        var rootError = assertThrows(BizException.class, () -> storage.test(missingRoot));
        assertTrue(rootError.getMessage().contains("根目录不存在或没有访问权限"));
        assertFalse(Files.exists(server.home.resolve("not-created")));
    }

    @Test void permissionRenameAndInterruptedTransferFailuresLeaveNoPartialFiles() throws Exception {
        Path source = source();
        server.denyWrites = true;
        assertThrows(Exception.class, () -> FtpStorage.upload(server.config(), "denied/file.bin", source));
        server.denyWrites = false;
        server.denyRename = true;
        assertThrows(Exception.class, () -> FtpStorage.upload(server.config(), "nested/rename.bin", source));
        server.denyRename = false;
        server.interruptUpload = true;
        assertThrows(Exception.class, () -> FtpStorage.upload(server.config(), "nested/interrupted.bin", source));
        try (var files = Files.walk(server.media)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
    }

    @Test void ftpRootCanContainUnicodeAndReadProbeNeverWrites() throws Exception {
        Files.createDirectory(server.media.resolve("中文目录"));
        var config = change(server.config(), "ftpRootDir", "/media/中文目录");
        storage.checkFtp(config);
        assertEquals(0, server.writes.get());
        FtpStorage.upload(config, "nested/data.bin", source());
        assertTrue(Files.exists(server.media.resolve("中文目录/nested/data.bin")));
        FtpStorage.delete(config, "nested/data.bin");
        FtpStorage.delete(config, "nested/data.bin");
    }

    @Test void rejectsInvalidParametersAndCommandOrPathInjection() throws Exception {
        for (var field : Map.of("ftpHost", "ftp://example.com", "ftpPort", 0, "ftpUsername", "user\r\nNOOP", "ftpPassword", "secret\nNOOP", "ftpRootDir", "/media/../escape", "domain", "ftp://example.com").entrySet()) {
            var invalid = change(server.config(), field.getKey(), field.getValue());
            assertThrows(BizException.class, () -> storage.save(invalid), field.getKey());
        }
        Path source = source();
        for (String key : List.of("../escape", "/absolute", "a/../b", "a\\b", "a/./b", "a//b", "a/", "a\r\nDELE b"))
            assertThrows(BizException.class, () -> FtpStorage.upload(server.config(), key, source), key);
        assertEquals(0, server.writes.get());
    }
}
