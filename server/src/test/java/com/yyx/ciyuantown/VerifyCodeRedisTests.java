package com.yyx.ciyuantown;

import com.yyx.ciyuantown.business.appauth.service.EmailCodeService;
import com.yyx.ciyuantown.common.exception.BizException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证码 Redis 链路测试（docs/redis-architecture-redesign.md 落地第 2 步）。
 *
 * <p>前提：Redis 功能开关生效（OS 环境变量或 .env 的 {@code REDIS_ENABLED=true}，即属性
 * {@code ciyuantown.redis.enabled=true}）且 127.0.0.1:6379 有实例；否则自动跳过
 * （DB 路径行为由 P2IntegrationTests 覆盖，该测试类固定关闭开关）。
 * 邮件使用 mock，每个用例独立 QQ 号，失败残留 key 由短 TTL 自清，不影响其他用例。
 */
@SpringBootTest(properties = {
    "spring.mail.username=vc-test@example.invalid",
    "spring.mail.password=test-only-no-network"
})
class VerifyCodeRedisTests {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired EmailCodeService codes;
    @Autowired RedisTemplate<String, String> redis;
    @Autowired Environment env;
    @MockitoBean JavaMailSender mail;

    String qq;
    String email;

    @BeforeEach
    void setup() {
        qq = "9" + String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
        email = qq + "@qq.com";
        whenCreateMimeMessage();
    }

    private void whenCreateMimeMessage() {
        when(mail.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private String dayKey() {
        return "ct:vc:rl:day:" + email + ":" + LocalDate.now(ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** 与生产开关同源（OS 环境变量 / .env / yml 默认），避免测试门控与运行态不一致 */
    private boolean redisEnabled() {
        return Boolean.parseBoolean(env.getProperty("ciyuantown.redis.enabled", "false"));
    }

    @Test
    void sendWritesCodeAndVerifyConsumesOnce() {
        assumeTrue(redisEnabled(), "需 ciyuantown.redis.enabled=true 且本地 6379 有 Redis 实例");
        codes.send(qq);
        String code = redis.opsForValue().get("ct:vc:" + email);
        assertThat(code).matches("[0-9]{6}");
        verify(mail).send(any(MimeMessage.class));

        codes.verify(qq, code);
        assertThat(redis.opsForValue().get("ct:vc:" + email)).isNull();
        assertThatThrownBy(() -> codes.verify(qq, code)).isInstanceOf(BizException.class)
            .hasMessage("验证码错误或已过期");
    }

    @Test
    void resendThrottledWithin60sWindow() {
        assumeTrue(redisEnabled(), "需 ciyuantown.redis.enabled=true 且本地 6379 有 Redis 实例");
        codes.send(qq);
        assertThatThrownBy(() -> codes.send(qq)).isInstanceOf(BizException.class)
            .hasMessage("发送过于频繁，请稍后再试");
        verify(mail, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void dailyLimitRejectsEleventhSend() {
        assumeTrue(redisEnabled(), "需 ciyuantown.redis.enabled=true 且本地 6379 有 Redis 实例");
        redis.opsForValue().set(dayKey(), "10");
        assertThatThrownBy(() -> codes.send(qq)).isInstanceOf(BizException.class)
            .hasMessage("今日发送次数已达上限");
        verify(mail, times(0)).send(any(MimeMessage.class));
    }

    @Test
    void wrongCodeKeepsKeyUsable() {
        assumeTrue(redisEnabled(), "需 ciyuantown.redis.enabled=true 且本地 6379 有 Redis 实例");
        codes.send(qq);
        String code = redis.opsForValue().get("ct:vc:" + email);
        assertThatThrownBy(() -> codes.verify(qq, "000000")).isInstanceOf(BizException.class)
            .hasMessage("验证码错误或已过期");
        assertThat(redis.opsForValue().get("ct:vc:" + email)).isEqualTo(code);
        codes.verify(qq, code);
    }

    @Test
    void mailFailureRollsBackReservation() {
        assumeTrue(redisEnabled(), "需 ciyuantown.redis.enabled=true 且本地 6379 有 Redis 实例");
        doThrow(new MailSendException("simulated transport error")).when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> codes.send(qq)).isInstanceOf(BizException.class)
            .hasMessage("验证码发送失败，请稍后重试");
        // 回滚验证码与 60s 窗口：可立即重试（等价 DB 事务回滚），当日计数不增加
        assertThat(redis.opsForValue().get("ct:vc:" + email)).isNull();
        assertThat(redis.opsForValue().get("ct:vc:rl:send:" + email)).isNull();
        assertThat(redis.opsForValue().get(dayKey())).isNull();

        reset(mail);
        whenCreateMimeMessage();
        codes.send(qq);
        verify(mail).send(any(MimeMessage.class));
    }
}
