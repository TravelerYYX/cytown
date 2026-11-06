package com.yyx.ciyuantown.business.level;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.appuser.service.AppUserAccessService;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LevelService {
    private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AppUserAccessService access;
    private final org.mybatis.spring.SqlSessionTemplate session;
    private final org.springframework.beans.factory.ObjectProvider<com.yyx.ciyuantown.business.checkin.CheckinService> checkins;
    private final Clock clock;
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");

    public LevelConfig config() {
        return readConfig(false);
    }
    /** Caller already holds the recipient row lock; use the latest committed config. */
    LevelConfig currentConfig() { return readConfig(true); }
    private LevelConfig readConfig(boolean currentRead) {
        var rows=jdbc.queryForList("SELECT version,config_json FROM community_level_config WHERE id=1"+(currentRead?" FOR SHARE":""));
        if(rows.isEmpty())throw new BizException("等级配置未初始化，请执行等级数据库迁移");
        try {
            var row=rows.getFirst();var c=json.readValue((String)row.get("config_json"),LevelConfig.class);
            return new LevelConfig(((Number)row.get("version")).longValue(),c.tiers(),c.levels(),c.rules());
        } catch(Exception e){throw new BizException("等级配置读取失败，请联系管理员");}
    }
    @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public LevelConfig save(LevelConfig input) {
        cleanup.track("community_level_config",1L);
        if(input==null)throw new BizException("等级配置不能为空");input.validate();
        // Lock users first, consistently with reward transactions; the config read is a current read.
        jdbc.queryForList("SELECT id FROM app_user ORDER BY id FOR UPDATE");
        var versions=jdbc.queryForList("SELECT version FROM community_level_config WHERE id=1 FOR UPDATE",Long.class);
        if(versions.isEmpty())throw new BizException("等级配置未初始化");
        if(input.version()!=versions.getFirst())throw new BizException("等级配置已被其他管理员修改，请刷新后重试");
        var next=new LevelConfig(versions.getFirst()+1,input.tiers(),input.levels(),input.rules());
        try {jdbc.update("UPDATE community_level_config SET version=?,config_json=? WHERE id=1",next.version(),json.writeValueAsString(next));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BizException("等级配置格式无效");}
        // Recalculate display levels without awarding retrospective promotion bonuses.
        for(var l:next.levels())jdbc.update("UPDATE app_user SET level=? WHERE experience>=? AND deleted=0",l.level(),l.requiredExp());
        session.clearCache();
        return next;
    }

    public record Progress(long experience, int level, String title, String fullTitle, String color,
                           String description, String badgeUrl, long levelStartExp, Long nextLevelExp,
                           long remainingExp, boolean maxLevel, boolean signedToday, int streakDays,
                           List<LevelConfig.Rule> rules, List<Map<String,Object>> today) {}
    @Transactional(readOnly=true)
    public Progress progress(Long userId) {
        var rows=jdbc.queryForList("SELECT experience FROM app_user WHERE id=? AND deleted=0 AND status=0",userId);
        if(rows.isEmpty())throw new BizException("用户不存在或已禁用");
        long exp=((Number)rows.getFirst().get("experience")).longValue();var c=config();var l=c.levelFor(exp);var t=c.tierFor(l.level());
        var dates=jdbc.queryForList("SELECT sign_date FROM user_level_signin WHERE user_id=? ORDER BY sign_date DESC LIMIT 1",java.sql.Date.class,userId);
        LocalDate today=LocalDate.now(clock);boolean signed=!dates.isEmpty()&&today.equals(dates.getFirst().toLocalDate());
        Integer streak=jdbc.queryForObject("SELECT COALESCE(MAX(streak_days),0) FROM user_level_signin WHERE user_id=? AND sign_date=? AND makeup=FALSE",Integer.class,userId,signed?today:today.minusDays(1));
        Long next=l.level()==60?null:c.levels().get(l.level()).requiredExp();
        var earned=jdbc.queryForList("SELECT action,COALESCE(SUM(amount),0) AS earned FROM user_level_ledger WHERE user_id=? AND earned_date=? GROUP BY action",userId,today);
        return new Progress(exp,l.level(),t.title(),t.fullTitle(),t.color(),t.description(),l.badgeUrl(),l.requiredExp(),next,
                next==null?0:Math.max(0,next-exp),next==null,signed,streak==null?0:streak,c.rules(),earned);
    }
    public record SignResult(long gained, Progress progress) {}
    @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public SignResult signIn(Long userId) {
        var result=checkins.getObject().claim(userId,null);
        return new SignResult(result.gained(),result.progress());
    }

    /** Calendar rewards have their own date keys and no interaction daily cap (makeup is per missed date). */
    @Transactional
    public long awardCalendar(Long userId,LocalDate date,long amount,boolean weekly) {
        return awardDailyReward(userId, amount, "sign_in", "calendar:"+date, weekly);
    }

    @Transactional
    public void awardCoinPunch(Long userId, LocalDate date, long amount) {
        awardDailyReward(userId, amount, "coin_punch", "coin-punch:"+date, false);
    }

    @Transactional
    public long awardMembership(Long userId,String orderId,long amount) {
        return awardDailyReward(userId,amount,"membership","membership:"+orderId,false);
    }

    private long awardDailyReward(Long userId, long amount, String action, String key, boolean weekly) {
        access.requireForUpdate(userId);
        if(consumed(userId,action,key))return 0;
        var c=readConfig(true);long old=jdbc.queryForObject("SELECT experience FROM app_user WHERE id=? FOR UPDATE",Long.class,userId);
        amount=Math.min(amount,LevelConfig.MAX_EXP-old);insert(userId,action,key,amount,LocalDate.now(clock));long total=old+amount;
        for(var tier:c.tiers())if(tier.id()!=1&&c.levelFor(old).level()<tier.minLevel()&&c.levelFor(total).level()>=tier.minLevel()&&!consumed(userId,"tier_bonus","tier:"+tier.id())){
            long bonus=Math.min(tier.entryBonus(),LevelConfig.MAX_EXP-total);insert(userId,"tier_bonus","tier:"+tier.id(),bonus,LocalDate.now(clock));total+=bonus;
        }
        jdbc.update("UPDATE app_user SET experience=?,level=? WHERE id=?",total,c.levelFor(total).level(),userId);session.clearCache();
        if(weekly)total+=awardLocked(userId,"streak_7",key);
        return total-old;
    }

    /** Caller holds the active recipient's app_user row lock within the content transaction. */
    @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public long award(Long userId,String action,String source) {
        if(userId==null||jdbc.queryForList("SELECT id FROM app_user WHERE id=? AND deleted=0 AND status=0 FOR UPDATE",userId).isEmpty())return 0;
        return awardLocked(userId,action,source);
    }
    /** Both interaction directions acquire user locks in the same order. */
    public void lockParticipants(Long actor,Long recipient) {
        if(recipient==null||Objects.equals(actor,recipient)) {
            jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",actor);
        } else {
            jdbc.queryForList("SELECT id FROM app_user WHERE id IN (?,?) ORDER BY id FOR UPDATE",Math.min(actor,recipient),Math.max(actor,recipient));
        }
    }
    private long awardLocked(Long userId,String action,String source) {
        if(!LevelConfig.ACTIONS.contains(action)||source==null||source.length()>160)throw new IllegalArgumentException("Invalid experience event");
        // Callers may already have a repeatable-read snapshot from before waiting for
        // this user lock. Locking reads must see the latest committed rewards/config.
        var c=readConfig(true);var rule=c.rule(action);LocalDate today=LocalDate.now(clock);
        if(consumed(userId,action,source))return 0;
        long daily=jdbc.queryForList("SELECT amount FROM user_level_ledger WHERE user_id=? AND action=? AND earned_date=? FOR UPDATE",Long.class,userId,action,today)
                .stream().mapToLong(Long::longValue).sum();
        long old=jdbc.queryForObject("SELECT experience FROM app_user WHERE id=? FOR UPDATE",Long.class,userId);
        long amount=rule.enabled()?Math.min(rule.experience(),Math.max(0,rule.dailyLimit()-daily)):0;
        amount=Math.min(amount,LevelConfig.MAX_EXP-old);
        // Even zero-reward events are consumed: a later unlike/re-like or re-review is never a new reward.
        insert(userId,action,source,amount,today);
        long total=old+amount;
        for(var tier:c.tiers()) {
            if(tier.id()==1||c.levelFor(old).level()>=tier.minLevel()||c.levelFor(total).level()<tier.minLevel())continue;
            String key="tier:"+tier.id();
            if(consumed(userId,"tier_bonus",key))continue;
            long bonus=Math.min(tier.entryBonus(),LevelConfig.MAX_EXP-total);
            insert(userId,"tier_bonus",key,bonus,today);total+=bonus;
        }
        jdbc.update("UPDATE app_user SET experience=?,level=? WHERE id=?",total,c.levelFor(total).level(),userId);
        session.clearCache();
        return total-old;
    }
    private void insert(Long user,String action,String source,long amount,LocalDate date) {
        jdbc.update("INSERT INTO user_level_ledger(user_id,action,event_key,amount,earned_date) VALUES(?,?,?,?,?)",user,action,source,amount,date);
    }
    private boolean consumed(Long user,String action,String key) {
        return !jdbc.queryForList("SELECT id FROM user_level_ledger WHERE user_id=? AND action=? AND event_key=? FOR UPDATE",user,action,key).isEmpty();
    }
    @Transactional(readOnly=true)
    public Map<String,Object> history(Long userId,int page,int size) {
        if(page<1||page>100000||size<1||size>100)throw new BizException("分页参数不合法");
        var rows=jdbc.queryForList("SELECT CAST(id AS CHAR) AS id,action,amount,earned_date AS earnedDate,create_time AS createTime FROM user_level_ledger WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",userId,size,(page-1)*size);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=?",Long.class,userId);
        return Map.of("records",rows,"total",total,"current",page,"pages",(total+size-1)/size);
    }
}
