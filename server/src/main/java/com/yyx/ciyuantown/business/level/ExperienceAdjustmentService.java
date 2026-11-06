package com.yyx.ciyuantown.business.level;

import com.yyx.ciyuantown.business.appuser.dto.ExperienceAdjustmentParam;
import com.yyx.ciyuantown.business.appuser.service.LoginAccountService;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDate;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class ExperienceAdjustmentService {
    private final JdbcTemplate jdbc;
    private final LoginAccountService accounts;
    private final LevelService levels;
    private final SqlSessionTemplate session;
    private final RedisHotspotService redis;
    private final ObjectProvider<CacheManager> caches;

    public record AdjustmentResult(String id, long delta, long beforeExperience, long afterExperience,
                                   int beforeLevel, int afterLevel) {}
    private record Target(long id, long experience) {}

    @Transactional
    public AdjustmentResult adjust(Long operator, boolean official, ExperienceAdjustmentParam input) {
        accounts.requireSuper(operator);
        if (input == null || input.id() == null || input.id() <= 0) throw new BizException("用户ID必须为正数");
        if (input.delta() == null || input.delta() == 0 || input.delta() < -LevelConfig.MAX_EXP || input.delta() > LevelConfig.MAX_EXP)
            throw new BizException("调整数量必须为非零整数，绝对值不能超过1000000000000");
        String reason = input.reason() == null ? "" : input.reason().strip();
        if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > 200) throw new BizException("调整原因须为1至200字");
        String requestId = input.requestId();
        if (requestId == null || !requestId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new BizException("请求标识必须为UUID");
        requestId = requestId.toLowerCase(Locale.ROOT);
        Target target = target(official, input.id(), true);
        var existing = jdbc.queryForList("SELECT * FROM user_level_ledger WHERE user_id=? AND action='admin_adjust' AND event_key=? FOR UPDATE", target.id(), requestId);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (number(row, "amount") != input.delta() || number(row, "operator_id") != operator || !reason.equals(row.get("reason")))
                throw new BizException("请求标识已用于其他调整，请刷新后重试");
            return result(row);
        }
        long total = target.experience() + input.delta(); // Both operands bounded by MAX_EXP: no long overflow.
        if (total < 0 || total > LevelConfig.MAX_EXP) throw new BizException("调整后积分必须在0至1000000000000之间");
        var config = levels.currentConfig();
        int beforeLevel = config.levelFor(target.experience()).level(), afterLevel = config.levelFor(total).level();
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("user_id", target.id()); values.put("action", "admin_adjust"); values.put("event_key", requestId);
        values.put("amount", input.delta()); values.put("earned_date", LocalDate.now(LevelService.ZONE));
        values.put("operator_id", operator); values.put("reason", reason);
        values.put("before_experience", target.experience()); values.put("after_experience", total);
        values.put("before_level", beforeLevel); values.put("after_level", afterLevel);
        Number key = new SimpleJdbcInsert(jdbc).withTableName("user_level_ledger").usingGeneratedKeyColumns("id")
                .usingColumns(values.keySet().toArray(String[]::new)).executeAndReturnKey(values);
        jdbc.update("UPDATE app_user SET experience=?,level=? WHERE id=?", total, afterLevel, target.id());
        session.clearCache();
        evictAfterCommit(target.id());
        return new AdjustmentResult(key.toString(), input.delta(), target.experience(), total, beforeLevel, afterLevel);
    }

    @Transactional(readOnly = true)
    public Map<String,Object> history(Long operator, boolean official, Long id, int page, int size) {
        accounts.requireSuper(operator);
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw new BizException("分页参数不合法");
        Target target = target(official, id, false);
        var rows = jdbc.queryForList("""
                SELECT CAST(l.id AS CHAR) AS id, l.amount AS delta,
                       CAST(l.operator_id AS CHAR) AS operatorId, a.nickname AS operatorName,
                       l.reason, l.before_experience AS beforeExperience, l.after_experience AS afterExperience,
                       l.before_level AS beforeLevel, l.after_level AS afterLevel, l.create_time AS createTime
                FROM user_level_ledger l LEFT JOIN admin_user a ON a.id=l.operator_id
                WHERE l.user_id=? AND l.action='admin_adjust' ORDER BY l.id DESC LIMIT ? OFFSET ?
                """, target.id(), size, (page-1)*size);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=? AND action='admin_adjust'", Long.class, target.id());
        return Map.of("records", rows, "total", count, "current", page, "size", size, "pages", (count+size-1)/size);
    }

    /** Official identities always lock admin -> community, also when entered from the APP list. */
    private Target target(boolean official, Long id, boolean lock) {
        if (id == null || id <= 0) throw new BizException("用户ID必须为正数");
        Long adminId = official ? id : null;
        if (!official) {
            var initial = jdbc.queryForList("SELECT official_admin_id FROM app_user WHERE id=? AND deleted=0", id);
            if (initial.isEmpty()) throw new BizException("用户不存在或已删除");
            Object linked = initial.getFirst().get("official_admin_id");
            if (linked != null) adminId = ((Number)linked).longValue();
        }
        String suffix = lock ? " FOR UPDATE" : "";
        if (adminId != null && jdbc.queryForList("SELECT id FROM admin_user WHERE id=? AND user_type=1 AND deleted=0"+suffix, adminId).isEmpty())
            throw new BizException("官方账号不存在或已删除");
        var rows = official
                ? jdbc.queryForList("SELECT id,experience FROM app_user WHERE official_admin_id=? AND deleted=0"+suffix, adminId)
                : jdbc.queryForList("SELECT id,experience FROM app_user WHERE id=? AND deleted=0"+suffix, id);
        if (rows.isEmpty()) throw new BizException(official ? "该官方账号尚未建立社区身份，无法调整积分" : "用户不存在或已删除");
        return new Target(number(rows.getFirst(), "id"), number(rows.getFirst(), "experience"));
    }
    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private static AdjustmentResult result(Map<String,Object> row) {
        return new AdjustmentResult(row.get("id").toString(), number(row,"amount"), number(row,"before_experience"),
                number(row,"after_experience"), (int)number(row,"before_level"), (int)number(row,"after_level"));
    }
    private void evictAfterCommit(long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                redis.evict("ct:user:profile:"+userId);
                var manager = caches.getIfAvailable();
                if (manager != null) for (String name : List.of("post-list", "post-detail")) {
                    try { var cache = manager.getCache(name); if (cache != null) cache.clear(); }
                    catch (RuntimeException e) { log.warn("积分调整已提交，清理缓存失败: user={} cache={}", userId, name, e); }
                }
            }
        });
    }
}
