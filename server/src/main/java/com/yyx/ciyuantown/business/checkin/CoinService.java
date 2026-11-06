package com.yyx.ciyuantown.business.checkin;

import java.math.BigDecimal;

import com.yyx.ciyuantown.business.appuser.service.AppUserAccessService;
import com.yyx.ciyuantown.business.level.LevelService;
import com.yyx.ciyuantown.business.level.LevelConfig;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CoinService {
    private static final int PAGE_SIZE = 20;
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final JdbcTemplate jdbc;
    private final AppUserAccessService access;
    private final LevelService levels;
    private final Clock clock;

    public record Rewards(Long points, BigDecimal coins) {
        public Rewards(long points,long coins) { this(points,BigDecimal.valueOf(coins)); }
    }
    public record State(BigDecimal balance, boolean punched, int streakDays, long points, BigDecimal coins) {}
    public record LedgerRow(String id, String title, String category, BigDecimal amount, String time) {}
    public record LedgerPage(List<LedgerRow> records, String nextCursor) {}
    private record Punch(long points, BigDecimal coins, int streakDays) {}

    public Rewards config() {
        return jdbc.queryForObject("SELECT points, coins FROM coin_punch_config WHERE id=1",
                (rs, row) -> new Rewards(rs.getLong("points"), rs.getBigDecimal("coins")));
    }

    public Rewards save(Rewards rewards) {
        if (!validReward(rewards.points()) || !CheckinConfig.validCoins(rewards.coins())) {
            throw new BizException("积分须为整数，次元币最多一位小数，范围均为 0 至 1000000");
        }
        jdbc.update("UPDATE coin_punch_config SET points=?, coins=? WHERE id=1", rewards.points(), rewards.coins());
        return rewards;
    }

    private boolean validReward(Long amount) {
        return amount != null && amount >= 0 && amount <= 1_000_000;
    }

    @Transactional(readOnly = true)
    public State state(Long user) {
        return state(user, LocalDate.now(clock));
    }

    private State state(Long user, LocalDate today) {
        BigDecimal balance = balance(user);
        var current = punch(user, today);
        if (current != null) {
            return new State(balance, true, current.streakDays(), current.points(), current.coins());
        }
        var previous = punch(user, today.minusDays(1));
        var rewards = config();
        return new State(balance, false, previous == null ? 0 : previous.streakDays(), rewards.points(), rewards.coins());
    }

    @Transactional
    @CacheEvict(cacheNames = {"post-list", "post-detail"}, allEntries = true)
    public State claim(Long user) {
        var account = access.requireForUpdate(user);
        var now = LocalDateTime.now(clock);
        var today = now.toLocalDate();
        BigDecimal balance = account.getCoinBalance() == null ? BigDecimal.ZERO : account.getCoinBalance();
        var current = punch(user, today);
        if (current != null) {
            return new State(balance, true, current.streakDays(), current.points(), current.coins());
        }
        var previous = punch(user, today.minusDays(1));
        int streak = previous == null ? 1 : previous.streakDays() + 1;
        var rewards = config();
        BigDecimal nextBalance = balance.add(rewards.coins());
        if (nextBalance.compareTo(CheckinService.MAX_COINS)>0) {
            throw new BizException("次元币余额已达上限");
        }
        long points = Math.min(rewards.points(), LevelConfig.MAX_EXP - account.getExperience());
        jdbc.update("INSERT INTO user_coin_punch(user_id,punch_date,points,coins,streak_days) VALUES(?,?,?,?,?)",
                user, today, points, rewards.coins(), streak);
        levels.awardCoinPunch(user, today, points);
        if (rewards.coins().signum()>0) {
            jdbc.update("UPDATE app_user SET coin_balance=? WHERE id=?", nextBalance, user);
            jdbc.update("INSERT INTO user_coin_ledger(user_id,event_key,amount,balance_after,create_time) VALUES(?,?,?,?,?)",
                    user, "coin-punch:" + today, rewards.coins(), nextBalance, now);
        }
        return new State(nextBalance, true, streak, points, rewards.coins());
    }

    private BigDecimal balance(Long user) {
        BigDecimal value = jdbc.queryForObject("SELECT coin_balance FROM app_user WHERE id=? AND deleted=0 AND status=0", BigDecimal.class, user);
        return value == null ? BigDecimal.ZERO : value;
    }

    private Punch punch(Long user, LocalDate date) {
        return jdbc.query("SELECT points, coins, streak_days FROM user_coin_punch WHERE user_id=? AND punch_date=?",
                (rs, row) -> new Punch(rs.getLong("points"), rs.getBigDecimal("coins"), rs.getInt("streak_days")),
                user, date).stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public LedgerPage ledger(Long user, String direction, Long cursor) {
        String sign = switch (direction) {
            case "earned" -> ">";
            case "spent" -> "<";
            default -> throw new BizException("明细类型无效");
        };
        var rows = jdbc.query("""
                SELECT l.id, l.event_key, l.amount, l.create_time, s.makeup
                FROM user_coin_ledger l
                LEFT JOIN user_level_signin s ON l.event_key LIKE 'checkin:%%'
                    AND s.user_id=l.user_id AND s.sign_date=SUBSTRING(l.event_key,9,10)
                WHERE l.user_id=? AND l.amount %s 0 AND l.create_time>=?
                    AND (? IS NULL OR (l.create_time,l.id) <
                        (SELECT c.create_time,c.id FROM user_coin_ledger c WHERE c.id=? AND c.user_id=? AND c.amount %s 0))
                ORDER BY l.create_time DESC,l.id DESC LIMIT ?
                """.formatted(sign, sign), (rs, row) -> {
            String key = rs.getString("event_key");
            BigDecimal amount = rs.getBigDecimal("amount");
            boolean punch = key.startsWith("coin-punch:");
            String title = key.startsWith("shop:") ? "商铺购买" : key.startsWith("membership:") ? "会员购买赠送" : punch ? "打卡" : key.endsWith(":makeup") ? "补签扣费"
                    : key.startsWith("checkin:") ? (rs.getBoolean("makeup") ? "补签奖励" : "签到奖励")
                    : (amount.signum()>0 ? "次元币入账" : "次元币扣减");
            return new LedgerRow(rs.getString("id"), title, punch ? "次元币任务" : "次元币明细",
                    amount, rs.getObject("create_time", LocalDateTime.class).format(DISPLAY_TIME));
        }, user, LocalDateTime.now(clock).minusMonths(8), cursor, cursor, user, PAGE_SIZE + 1);
        if (rows.size() <= PAGE_SIZE) {
            return new LedgerPage(rows, "");
        }
        return new LedgerPage(rows.subList(0, PAGE_SIZE), rows.get(PAGE_SIZE - 1).id());
    }
}
