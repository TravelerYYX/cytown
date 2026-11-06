package com.yyx.ciyuantown.business.appauth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.appauth.entity.VerifyCode;
import com.yyx.ciyuantown.business.appauth.mapper.VerifyCodeMapper;
import com.yyx.ciyuantown.business.appauth.service.EmailCodeService;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * 邮件验证码服务（docs/redis-architecture-redesign.md 落地第 2 步）。
 *
 * <p>{@code ciyuantown.redis.enabled=true} 时优先走 Redis 短 TTL 路径：
 * 验证码 {@code ct:vc:{email}}（5min）、60s 发码窗口 {@code ct:vc:rl:send:{email}}、
 * 当日上限 {@code ct:vc:rl:day:{email}:{yyyyMMdd}}（过期到次日零点）；任一 Redis 操作抛
 * {@link DataAccessException}（如实例不可用）即回退 MySQL {@code verify_code} 原实现（256 分片锁）。
 * 开关关闭时行为与历史完全一致。
 *
 * <p>两条路径对外语义一致：邮件失败整体回滚（DB 事务回滚 / Redis 删除预留 key 与计数）、
 * 60s 节流、每日上限、验证码一次性消费，错误文案不变；Redis 仅用于短 TTL 临时态，
 * 不承载任何持久数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeServiceImpl implements EmailCodeService {
    private final VerifyCodeMapper codes;
    private final JavaMailSender mail;
    private final TransactionTemplate transactions;
    private static final SecureRandom RANDOM = new SecureRandom();
    // 项目为单实例部署；固定分片锁避免首次发码并发穿透，也避免锁集合无限增长。（仅 DB 回退路径使用）
    private final Object[] locks = IntStream.range(0, 256).mapToObj(i -> new Object()).toArray();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private RedisTemplate<String, String> redis;
    @Value("${ciyuantown.redis.enabled:true}") private boolean redisEnabled;
    @Value("${spring.mail.username:}") private String from;
    @Value("${spring.mail.password:}") private String mailPassword;
    @Value("${ciyuantown.verify-code.expire-minutes:5}") private int minutes;
    @Value("${ciyuantown.verify-code.resend-interval-seconds:60}") private int interval;
    @Value("${ciyuantown.verify-code.daily-limit:10}") private int dailyLimit;

    private static LocalDateTime now() { return LocalDateTime.now(ZONE); }
    private String email(String qq) {
        if (qq == null || !qq.matches("[0-9]{5,11}")) throw new BizException("QQ号格式不正确");
        return qq + "@qq.com";
    }
    private LambdaQueryWrapper<VerifyCode> latest(String email) {
        return new LambdaQueryWrapper<VerifyCode>().eq(VerifyCode::getEmail, email)
            .orderByDesc(VerifyCode::getCreateTime, VerifyCode::getId);
    }

    // ---- Key 与 TTL（对齐设计文档 §2）----
    private String vcKey(String email) { return "ct:vc:" + email; }
    private String rlSendKey(String email) { return "ct:vc:rl:send:" + email; }
    private String rlDayKey(String email) {
        return "ct:vc:rl:day:" + email + ":" + LocalDate.now(ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
    }
    /** 距次日零点(Asia/Shanghai)的秒数，保证当日计数 key 到点自清 */
    private long dayTtlSeconds() {
        long nowSeconds = Instant.now().getEpochSecond();
        long midnight = LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE).toEpochSecond();
        return Math.max(1, midnight - nowSeconds);
    }

    // ---- 发送 ----
    @Override
    public void send(String qqNumber) {
        if (from == null || from.isBlank() || mailPassword == null || mailPassword.isBlank())
            throw new BizException("邮件服务未配置，请通过 scripts/dev.ps1 启动或配置 SMTP_USERNAME 和 SMTP_PASSWORD");
        String email = email(qqNumber);
        if (redisEnabled) {
            try {
                redisSend(email);
                return;
            } catch (DataAccessException e) {
                log.warn("验证码 Redis 链路不可用，回退 DB 实现: {}", e.getMessage());
            }
        }
        dbSend(email);
    }

    private void redisSend(String email) {
        String codeKey = vcKey(email), sendKey = rlSendKey(email), dayKey = rlDayKey(email);
        // 60s 节流：仅当窗口 key 不存在时原子占用；邮件失败时删除（等价 DB 事务回滚后可立即重试）
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(sendKey, "1", Duration.ofSeconds(interval))))
            throw new BizException("发送过于频繁，请稍后再试");
        // 当日上限预检（发送成功后才 INCR，与 DB「插入记录即计数」时机一致）
        String dayValue = redis.opsForValue().get(dayKey);
        long sentToday = 0;
        if (dayValue != null) {
            try { sentToday = Long.parseLong(dayValue); } catch (NumberFormatException ignored) { }
        }
        if (sentToday >= dailyLimit) throw new BizException("今日发送次数已达上限");

        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(codeKey, code, Duration.ofMinutes(minutes));
        try {
            sendMail(email, code);
        } catch (BizException e) {
            rollbackReservation(codeKey, sendKey);
            throw e;
        }
        Long count = redis.opsForValue().increment(dayKey);
        if (count != null && count == 1) redis.expire(dayKey, dayTtlSeconds(), TimeUnit.SECONDS);
    }

    /** 发送失败回滚 Redis 预留态：删除验证码与 60s 窗口（当日计数尚未累加，无需回滚） */
    private void rollbackReservation(String codeKey, String sendKey) {
        try {
            redis.delete(codeKey);
            redis.delete(sendKey);
        } catch (DataAccessException ignored) {
            // Redis 已不可用：key 均带短 TTL，交由过期清理；不掩盖原始错误
        }
    }

    private void dbSend(String email) {
        synchronized (locks[Math.floorMod(email.hashCode(), locks.length)]) {
            transactions.executeWithoutResult(status -> {
                LocalDateTime time = now();
                var previous = codes.selectOne(latest(email).last("LIMIT 1"));
                if (previous != null && previous.getCreateTime().plusSeconds(interval).isAfter(time))
                    throw new BizException("发送过于频繁，请稍后再试");
                LocalDateTime start = time.toLocalDate().atStartOfDay();
                long count = codes.selectCount(new LambdaQueryWrapper<VerifyCode>()
                    .eq(VerifyCode::getEmail, email).ge(VerifyCode::getCreateTime, start)
                    .lt(VerifyCode::getCreateTime, start.plusDays(1)));
                if (count >= dailyLimit) throw new BizException("今日发送次数已达上限");
                var record = new VerifyCode();
                record.setEmail(email);
                record.setCode(String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000)));
                record.setCreateTime(time); record.setExpireTime(time.plusMinutes(minutes)); record.setUsed(false);
                codes.insert(record);
                try {
                    var message = mail.createMimeMessage();
                    var helper = new MimeMessageHelper(message, false, "UTF-8");
                    helper.setFrom(from, "次元町"); helper.setTo(email); helper.setSubject("次元町验证码");
                    helper.setText("<div><h2>次元町验证码</h2><p>你的验证码是：<strong>" + record.getCode()
                        + "</strong></p><p>" + minutes + " 分钟内有效，请勿向他人透露。如非本人操作，请忽略此邮件。</p></div>", true);
                    mail.send(message);
                } catch (Exception e) {
                    // 事务回滚插入记录；不将 SMTP 返回、验证码或授权码写入日志。
                    throw new BizException("验证码发送失败，请稍后重试");
                }
            });
        }
    }

    private void sendMail(String email, String code) {
        try {
            var message = mail.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from, "次元町"); helper.setTo(email); helper.setSubject("次元町验证码");
            helper.setText("<div><h2>次元町验证码</h2><p>你的验证码是：<strong>" + code
                + "</strong></p><p>" + minutes + " 分钟内有效，请勿向他人透露。如非本人操作，请忽略此邮件。</p></div>", true);
            mail.send(message);
        } catch (Exception e) {
            // 不将 SMTP 返回、验证码或授权码写入日志。
            throw new BizException("验证码发送失败，请稍后重试");
        }
    }

    // ---- 校验 ----
    @Override
    public void verify(String qqNumber, String code) {
        String email = email(qqNumber);
        if (redisEnabled) {
            try {
                redisVerify(email, code);
                return;
            } catch (DataAccessException e) {
                log.warn("验证码 Redis 链路不可用，回退 DB 实现: {}", e.getMessage());
            }
        }
        dbVerify(email, code);
    }

    private void redisVerify(String email, String code) {
        // 取码即删：一次性消费；过期由 5min TTL 兜底（等价 DB used=1 + expire_time）
        String stored = redis.opsForValue().get(vcKey(email));
        if (code == null || !code.matches("[0-9]{6}") || stored == null || !stored.equals(code))
            throw new BizException("验证码错误或已过期");
        redis.delete(vcKey(email));
    }

    private void dbVerify(String email, String code) {
        transactions.executeWithoutResult(status -> {
            var record = codes.selectOne(latest(email).last("LIMIT 1 FOR UPDATE"));
            if (record == null || Boolean.TRUE.equals(record.getUsed()) || !record.getExpireTime().isAfter(now())
                || code == null || !code.matches("[0-9]{6}") || !record.getCode().equals(code))
                throw new BizException("验证码错误或已过期");
            record.setUsed(true);
            codes.updateById(record);
        });
    }
}
