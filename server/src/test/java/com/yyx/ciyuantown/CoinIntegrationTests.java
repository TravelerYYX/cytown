package com.yyx.ciyuantown;

import com.yyx.ciyuantown.business.checkin.CheckinService;
import com.yyx.ciyuantown.business.checkin.CoinService;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"ciyuantown.redis.enabled=false"})
class CoinIntegrationTests {
    @Autowired CoinService coins;
    @Autowired CheckinService checkins;
    @Autowired JdbcTemplate db;
    @MockitoBean Clock clock;
    private final List<Long> users = new ArrayList<>();
    private CoinService.Rewards savedConfig;

    @BeforeEach
    void setup() {
        time("2026-11-03T23:59:59");
        savedConfig = coins.config();
        coins.save(new CoinService.Rewards(25L, 10L));
    }

    @AfterEach
    void cleanup() {
        for (Long user : users) {
            for (String table : List.of("user_coin_ledger", "user_avatar_frame", "user_level_signin", "user_level_ledger")) {
                db.update("DELETE FROM " + table + " WHERE user_id=?", user);
            }
            db.update("DELETE FROM app_user WHERE id=?", user);
        }
        if (savedConfig != null) coins.save(savedConfig);
    }

    @Test
    void independentRewardsAndConcurrentRetries() throws Exception {
        long user = createUser();
        var checkin = checkins.claim(user, null);
        assertFalse(coins.state(user).punched());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return coins.claim(user); });
            var second = pool.submit(() -> { start.await(); return coins.claim(user); });
            start.countDown();
            var result = first.get(10, TimeUnit.SECONDS);
            assertEquals(result, second.get(10, TimeUnit.SECONDS));
            assertEquals(0, result.balance().compareTo(checkin.state().balance().add(BigDecimal.TEN)));
            assertEquals(25, result.points());
            assertEquals(1, result.streakDays());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM user_coin_punch WHERE user_id=?", Integer.class, user));
            assertEquals(25L, db.queryForObject("SELECT amount FROM user_level_ledger WHERE user_id=? AND action='coin_punch'", Long.class, user));
        }

        coins.save(new CoinService.Rewards(30L, 15L));
        assertEquals(0, coins.claim(user).coins().compareTo(BigDecimal.valueOf(10)));
        time("2026-11-04T00:00:00");
        assertFalse(coins.state(user).punched());
        assertEquals(0, coins.state(user).coins().compareTo(BigDecimal.valueOf(15)));
        assertEquals(2, coins.claim(user).streakDays());
        time("2026-11-06T00:00:00");
        assertEquals(0, coins.state(user).streakDays());
        assertEquals(1, coins.claim(user).streakDays());
    }

    @Test
    void ledgerFailureRollsBackRewardsAndDailyRecord() {
        long user = createUser();
        db.update("INSERT INTO user_coin_ledger(user_id,event_key,amount,balance_after) VALUES(?,'coin-punch:2026-11-03',10,10)", user);
        assertThrows(RuntimeException.class, () -> coins.claim(user));
        assertFalse(coins.state(user).punched());
        assertEquals(0, coins.state(user).balance().compareTo(BigDecimal.valueOf(0)));
        assertEquals(0L, db.queryForObject("SELECT experience FROM app_user WHERE id=?", Long.class, user));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=?", Integer.class, user));
    }

    @Test
    void ledgerPaginatesByTimeAndIdAndKeepsAccountsSeparate() {
        long user = createUser();
        long otherUser = createUser();
        for (int i = 0; i < 21; i++) addLedger(user, "coin-punch:test-" + i, i + 1, "2026-11-03T08:00:00");
        addLedger(user, "old", 100, "2026-03-03T23:59:58");
        addLedger(user, "boundary", 101, "2026-03-03T23:59:59");
        addLedger(otherUser, "other", 200, "2026-11-03T08:00:00");
        db.update("INSERT INTO user_level_signin(user_id,sign_date,streak_days,makeup) VALUES(?,?,0,TRUE)",
                user, LocalDate.parse("2026-11-01"));
        addLedger(user, "checkin:2026-11-01:reward", 30, "2026-11-03T10:00:00");
        addLedger(user, "checkin:2026-11-01:makeup", -10, "2026-11-03T10:00:00");

        var first = coins.ledger(user, "earned", null);
        assertEquals(20, first.records().size());
        assertEquals("补签奖励", first.records().getFirst().title());
        assertEquals(0, first.records().get(1).amount().compareTo(BigDecimal.valueOf(21)));
        var second = coins.ledger(user, "earned", Long.valueOf(first.nextCursor()));
        assertEquals(List.of(2L, 1L, 101L), second.records().stream().map(row -> row.amount().longValueExact()).toList());
        assertEquals("", second.nextCursor());
        var debit = coins.ledger(user, "spent", null).records().getFirst();
        assertEquals("补签扣费", debit.title());
        assertEquals(0, debit.amount().compareTo(BigDecimal.valueOf(-10)));
        assertEquals("2026-11-03 10:00", debit.time());
        assertEquals(1, coins.ledger(otherUser, "earned", null).records().size());
        assertThrows(BizException.class, () -> coins.ledger(user, "all", null));
    }

    private void time(String value) {
        when(clock.getZone()).thenReturn(CheckinService.ZONE);
        when(clock.instant()).thenReturn(LocalDateTime.parse(value).atZone(CheckinService.ZONE).toInstant());
    }

    private long createUser() {
        String qq = "8" + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L);
        long user = new SimpleJdbcInsert(db).withTableName("app_user").usingGeneratedKeyColumns("id")
                .usingColumns("qq_number", "email", "nickname")
                .executeAndReturnKey(Map.of("qq_number", qq, "email", qq + "@qq.com", "nickname", "打卡测试")).longValue();
        users.add(user);
        return user;
    }

    private void addLedger(long user, String event, long amount, String time) {
        db.update("INSERT INTO user_coin_ledger(user_id,event_key,amount,balance_after,create_time) VALUES(?,?,?,0,?)",
                user, event, amount, LocalDateTime.parse(time));
    }
}
