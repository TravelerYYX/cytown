package com.yyx.ciyuantown.common.diagnostics;

import com.yyx.ciyuantown.common.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DiagnosticTests {
    @TempDir Path root;

    @Test void reportsEachFailureWithoutLeakingCredentialsAndCachesResults() {
        var probes = mock(DependencyChecks.class);
        AtomicInteger calls = new AtomicInteger();
        when(probes.checks()).thenReturn(List.of(
                new DependencyChecks.Check("mysql", "数据库", "检查数据库配置", () -> {
                    calls.incrementAndGet();
                    throw new SQLException("password=do-not-expose", "28000", 1045);
                }),
                new DependencyChecks.Check("redis", "缓存", "检查缓存配置", () -> DependencyChecks.Outcome.up("PONG"))
        ));
        try (var service = new CloseableService(probes, 1000)) {
            var report = service.instance.report();
            assertThat(report.status()).isEqualTo("DOWN");
            assertThat(report.checks()).extracting(DiagnosticReport.Item::status).containsExactly("DOWN", "UP");
            assertThat(report.checks().getFirst().message()).contains("账号或密码错误").doesNotContain("do-not-expose");
            assertThat(service.instance.report()).isSameAs(report);
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Test void aStalledDependencyDoesNotHideCompletedResults() {
        var probes = mock(DependencyChecks.class);
        when(probes.checks()).thenReturn(List.of(
                new DependencyChecks.Check("mysql", "数据库", "检查数据库", () -> {
                    new CountDownLatch(1).await();
                    return DependencyChecks.Outcome.up("unexpected");
                }),
                new DependencyChecks.Check("redis", "缓存", "检查缓存", () -> DependencyChecks.Outcome.up("PONG"))
        ));
        try (var service = new CloseableService(probes, 150)) {
            long started = System.nanoTime();
            var report = service.instance.report();
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2000);
            assertThat(report.checks().getFirst().message()).contains("超时");
            assertThat(report.checks().get(1).status()).isEqualTo("UP");
        }
    }

    @Test void disabledRedisIsSkippedWithoutOpeningAConnection() {
        var redis = mock(RedisConnectionFactory.class);
        var probes = dependencies(redis, new MockEnvironment().withProperty("ciyuantown.redis.enabled", "false"), mock(StorageService.class), mailProvider());
        assertThat(probes.redis().status()).isEqualTo("SKIPPED");
        verifyNoInteractions(redis);
    }

    @Test void redisPingUsesRealFactoryAndClosesItsConnection() {
        var redis = mock(RedisConnectionFactory.class);
        var connection = mock(RedisConnection.class);
        when(redis.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        var probes = dependencies(redis, new MockEnvironment(), mock(StorageService.class), mailProvider());
        assertThat(probes.redis().status()).isEqualTo("UP");
        verify(connection).ping();
        verify(connection).close();
    }

    @Test void localStorageTestsReadWriteAndDeletesOnlyItsTemporaryFile() throws Exception {
        Path existing = Files.writeString(root.resolve("existing.txt"), "keep");
        var storage = mock(StorageService.class);
        when(storage.active()).thenReturn(new StorageService.Config("test", "local", root.toString(), "", "", "", "", "", ""));
        var probes = dependencies(mock(RedisConnectionFactory.class), new MockEnvironment(), storage, mailProvider());
        assertThat(probes.storage().status()).isEqualTo("UP");
        assertThat(Files.readString(existing)).isEqualTo("keep");
        try (var files = Files.list(root)) { assertThat(files.toList()).containsExactly(existing); }
        assertThat(probes.mediaDomain().status()).isEqualTo("SKIPPED");
        verify(storage, never()).test(any());
        verify(storage, never()).put(any(), any(), any(), any());
    }

    @Test void missingSmtpAndExecutablesAreReportedAsConfigurationFailures() throws Exception {
        var provider = mailProvider();
        when(provider.getIfAvailable()).thenReturn(new JavaMailSenderImpl());
        var probes = dependencies(mock(RedisConnectionFactory.class), new MockEnvironment(), mock(StorageService.class), provider);
        assertThat(probes.smtp().status()).isEqualTo("DOWN");
        assertThat(probes.smtp().message()).contains("授权码未配置");
        assertThat(probes.ffmpeg().message()).contains("FFMPEG_PATH", "FFPROBE_PATH");
    }

    @Test void ftpDiagnosticChecksAuthenticationAndRootWithoutWriting() throws Exception {
        try (var server = new com.yyx.ciyuantown.common.storage.FtpFixture(root)) {
            var storage = mock(StorageService.class);
            when(storage.active()).thenReturn(server.config());
            doAnswer(i -> { com.yyx.ciyuantown.common.storage.FtpStorage.check(i.getArgument(0)); return null; })
                    .when(storage).checkFtp(any());
            var probes = dependencies(mock(RedisConnectionFactory.class), new MockEnvironment(), storage, mailProvider());
            assertThat(probes.storage().status()).isEqualTo("UP");
            verify(storage).checkFtp(server.config());
            verify(storage, never()).test(any());
            assertThat(server.writes.get()).isZero();
            var c = server.config();
            var wrong = new StorageService.Config(c.id(), c.provider(), "", "", "", "", "", "", c.domain(),
                    c.ftpHost(), c.ftpPort(), c.ftpUsername(), "do-not-expose", c.ftpRootDir());
            try {
                com.yyx.ciyuantown.common.storage.FtpStorage.check(wrong);
                org.junit.jupiter.api.Assertions.fail("Expected authentication failure");
            } catch (com.yyx.ciyuantown.common.storage.FtpStorage.Failure error) {
                assertThat(DiagnosticFailures.describe(error)).contains("FTP 登录认证失败").doesNotContain("do-not-expose");
            }
        }
    }

    @Test void connectionFailuresAndRedisAuthenticationAreDescribedWithoutRawMessages() {
        assertThat(DiagnosticFailures.describe(new IllegalStateException("secret", new ConnectException("secret")))).contains("连接被拒绝").doesNotContain("secret");
        assertThat(DiagnosticFailures.describe(new IllegalStateException("WRONGPASS secret"))).contains("Redis 认证失败").doesNotContain("secret");
        assertThat(DiagnosticFailures.describe(new SQLException("secret", "42S02", 1146))).contains("缺少业务表").doesNotContain("secret");
        assertThat(DiagnosticFailures.describe(new SQLException("connection timed out", new SQLException("secret", "28000", 1045))))
                .contains("账号或密码错误").doesNotContain("secret");
    }

    @Test void databaseFailureIsExplainedAsTheCauseOfUnreadableStorageConfiguration() throws Exception {
        var storage = mock(StorageService.class);
        when(storage.active()).thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("secret", new SQLException("secret", "28000", 1045)));
        var probes = dependencies(mock(RedisConnectionFactory.class), new MockEnvironment(), storage, mailProvider());
        assertThat(probes.storage().message()).contains("无法从数据库读取", "账号或密码错误").doesNotContain("secret");
        assertThat(probes.mediaDomain().suggestion()).contains("先处理 MySQL");
    }

    @Test void schemaListsMissingTablesAndMappedColumns() throws Exception {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "diagnostics-tests");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, com.yyx.ciyuantown.business.sys.entity.AdminUser.class);
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("admin_user");
        when(rows.getString(2)).thenReturn("id");
        var probes = new DependencyChecks(new JdbcTemplate(source), mock(RedisConnectionFactory.class), mailProvider(), mock(StorageService.class), new MockEnvironment());
        var result = probes.schema();
        assertThat(result.status()).isEqualTo("DOWN");
        assertThat(result.message()).contains("缺少表", "media_job", "缺少字段", "admin_user.token_version");
        verify(statement).setQueryTimeout(3);
        verify(connection).close();
    }

    private static DependencyChecks dependencies(RedisConnectionFactory redis, MockEnvironment env, StorageService storage,
                                                  ObjectProvider<JavaMailSender> mail) {
        return new DependencyChecks(new JdbcTemplate(mock(DataSource.class)), redis, mail, storage, env);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> mailProvider() { return mock(ObjectProvider.class); }

    private static class CloseableService implements AutoCloseable {
        final DiagnosticService instance;
        CloseableService(DependencyChecks probes, long timeout) { instance = new DiagnosticService(probes, timeout); }
        @Override public void close() { instance.close(); }
    }
}
