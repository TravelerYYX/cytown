package com.yyx.ciyuantown.business.checkin;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.appuser.service.AppUserAccessService;
import com.yyx.ciyuantown.business.level.LevelService;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class CheckinService {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public static final BigDecimal MAX_COINS=BigDecimal.valueOf(1_000_000_000_000L);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AppUserAccessService access;
    private final LevelService levels;
    private final Clock clock;
    private final org.springframework.beans.factory.ObjectProvider<com.yyx.ciyuantown.business.membership.MembershipStore> memberships;
    private CheckinConfig read(boolean lock){
        var rows=jdbc.queryForList("SELECT version,config_json FROM checkin_config WHERE id=1"+(lock?" FOR SHARE":""));
        if(rows.isEmpty())throw new BizException("请先执行签到数据库迁移");
        try{var r=rows.getFirst();var c=json.readValue((String)r.get("config_json"),CheckinConfig.class);return new CheckinConfig(((Number)r.get("version")).longValue(),c.makeupPrice(),c.coinIcon(),c.experienceIcon(),c.frames(),c.days());}
        catch(Exception e){throw new BizException("签到配置读取失败");}
    }
    public CheckinConfig config(){return read(false);}
    @Transactional @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public CheckinConfig save(CheckinConfig c){
        if(c==null)throw new BizException("配置不能为空");c.validate();
        var version=jdbc.queryForObject("SELECT version FROM checkin_config WHERE id=1 FOR UPDATE",Long.class);
        if(version==null||version!=c.version())throw new BizException("配置已被其他管理员修改，请重新加载");
        // Existing grants retain their catalog entry. Removing a frame could strand an owned item.
        for(var f:read(false).frames())if(c.frames().stream().noneMatch(n->n.id().equals(f.id())))throw new BizException("已有头像框不能删除，可停止配置为奖励");
        var next=new CheckinConfig(version+1,c.makeupPrice(),c.coinIcon(),c.experienceIcon(),c.frames(),c.days());
        jdbc.update("UPDATE checkin_config SET version=?,config_json=? WHERE id=1",next.version(),encode(next));return next;
    }
    public record DayView(int day,CheckinConfig.Day reward,boolean signed,boolean makeup,boolean today,boolean canMakeup){}
    public record State(String today,String month,int daysInMonth,int signedDays,int missedDays,BigDecimal balance,BigDecimal makeupPrice,
                        boolean signedToday,List<DayView> days,CheckinConfig config){}
    @Transactional(readOnly=true)
    public State state(Long user){
        LocalDate today=LocalDate.now(clock);YearMonth month=YearMonth.from(today);var c=config();
        var rows=jdbc.queryForList("SELECT sign_date,makeup,reward_json FROM user_level_signin WHERE user_id=? AND sign_date>=? AND sign_date<?",user,month.atDay(1),month.plusMonths(1).atDay(1));
        var signed=new HashMap<Integer,Map<String,Object>>();for(var r:rows)signed.put(((java.sql.Date)r.get("sign_date")).toLocalDate().getDayOfMonth(),r);
        List<DayView> days=new ArrayList<>();int missed=0;
        for(int d=1;d<=month.lengthOfMonth();d++){
            var row=signed.get(d);var reward=c.days().get(d-1);
            if(row!=null&&row.get("reward_json")!=null)try{reward=json.readValue((String)row.get("reward_json"),CheckinConfig.Day.class);}catch(Exception e){throw new BizException("签到奖励记录读取失败");}
            boolean can=d<today.getDayOfMonth()&&row==null;if(can)missed++;
            days.add(new DayView(d,reward,row!=null,row!=null&&Boolean.TRUE.equals(row.get("makeup")),d==today.getDayOfMonth(),can));
        }
        BigDecimal balance=jdbc.queryForObject("SELECT coin_balance FROM app_user WHERE id=? AND deleted=0 AND status=0",BigDecimal.class,user);
        return new State(today.toString(),month.toString(),month.lengthOfMonth(),signed.size(),missed,balance==null?BigDecimal.ZERO:balance,c.makeupPrice(),signed.containsKey(today.getDayOfMonth()),days,c);
    }
    public record Claim(boolean alreadySigned,long gained,CheckinConfig.Day reward,State state,LevelService.Progress progress){}
    @Transactional @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public Claim claim(Long user,LocalDate requested){
        return claim(user,requested,null);
    }
    @Transactional @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public Claim claim(Long user,LocalDate requested,Long expectedVersion){
        access.requireForUpdate(user);LocalDate today=LocalDate.now(clock);LocalDate date=requested==null?today:requested;
        boolean makeup=!date.equals(today);
        if(makeup&&(!YearMonth.from(date).equals(YearMonth.from(today))||!date.isBefore(today)))throw new BizException("只能补签本月过去未签到的日期");
        var existing=jdbc.queryForList("SELECT sign_date FROM user_level_signin WHERE user_id=? AND sign_date=? FOR UPDATE",user,date);
        if(!existing.isEmpty())return new Claim(true,0,null,state(user),levels.progress(user));
        var c=read(true);var reward=c.days().get(date.getDayOfMonth()-1);
        if(expectedVersion!=null&&expectedVersion!=c.version())throw new BizException("签到配置已更新，请刷新后确认补签价格和奖励");
        BigDecimal balance=jdbc.queryForObject("SELECT coin_balance FROM app_user WHERE id=? FOR UPDATE",BigDecimal.class,user);
        if(makeup&&balance.compareTo(c.makeupPrice())<0)throw new BizException("次元币不足，无法补签");
        if(balance.subtract(makeup?c.makeupPrice():BigDecimal.ZERO).add(reward.coins()).compareTo(MAX_COINS)>0)throw new BizException("次元币余额已达上限");
        int streak=0;
        if(!makeup){var previous=jdbc.queryForList("SELECT streak_days FROM user_level_signin WHERE user_id=? AND sign_date=? AND makeup=FALSE FOR UPDATE",Integer.class,user,today.minusDays(1));streak=(previous.isEmpty()?0:previous.getFirst())+1;}
        jdbc.update("INSERT INTO user_level_signin(user_id,sign_date,streak_days,makeup,reward_json,config_version,claimed_at) VALUES(?,?,?,?,?,?,?)",user,date,streak,makeup,encode(reward),c.version(),LocalDateTime.now(clock));
        if(makeup&&c.makeupPrice().signum()>0)coin(user,date,"makeup",c.makeupPrice().negate());
        if(reward.coins().signum()>0)coin(user,date,"reward",reward.coins());
        if(!reward.frameId().isEmpty())grantFrame(user,reward.frameId(),reward.frameDays());
        long gained=levels.awardCalendar(user,date,reward.experience(),!makeup&&streak%7==0);
        return new Claim(false,gained,reward,state(user),levels.progress(user));
    }
    private void coin(Long user,LocalDate date,String kind,BigDecimal delta){
        jdbc.update("UPDATE app_user SET coin_balance=coin_balance+? WHERE id=?",delta,user);
        BigDecimal balance=jdbc.queryForObject("SELECT coin_balance FROM app_user WHERE id=?",BigDecimal.class,user);
        jdbc.update("INSERT INTO user_coin_ledger(user_id,event_key,amount,balance_after) VALUES(?,?,?,?)",user,"checkin:"+date+":"+kind,delta,balance);
    }
    private void grantFrame(Long user,String id,int duration){
        var rows=jdbc.queryForList("SELECT expires_at FROM user_avatar_frame WHERE user_id=? AND frame_id=? FOR UPDATE",user,id);
        if(!rows.isEmpty()&&rows.getFirst().get("expires_at")==null)return;
        LocalDateTime now=LocalDateTime.now(clock),expiry=null;
        if(duration>0){var old=rows.isEmpty()?null:dateTime(rows.getFirst().get("expires_at"));expiry=(old!=null&&old.isAfter(now)?old:now).plusDays(duration);}
        jdbc.update("INSERT INTO user_avatar_frame(user_id,frame_id,expires_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at)",user,id,expiry);
        Long displayExpiry=expiry==null?null:expiry.atZone(ZONE).toInstant().toEpochMilli();
        if(displayExpiry!=null&&id.startsWith("mem-"))try{displayExpiry=Math.max(displayExpiry,memberships.getObject().requireFrame(user,id));}catch(BizException ignored){}
        jdbc.update("UPDATE app_user SET avatar_frame_expires_at=? WHERE id=? AND avatar_frame=?",displayExpiry,user,id);
    }
    public List<Map<String,Object>> owned(Long user){return jdbc.query("SELECT frame_id,expires_at FROM user_avatar_frame WHERE user_id=?",(rs,n)->{Map<String,Object> r=new HashMap<>();r.put("frameId",rs.getString(1));var t=rs.getTimestamp(2);r.put("expiresAt",t==null?null:t.toLocalDateTime().atZone(ZONE).toInstant().toEpochMilli());return r;},user);}
    public Long requireFrame(Long user,String frame){
        if(Set.of("","star-sea","sakura-mist").contains(frame))return null;
        boolean memberFrame=frame.startsWith("mem-");
        if(!memberFrame&&!frame.startsWith("shop-")&&config().frames().stream().noneMatch(f->f.id().equals(frame)))throw new BizException("头像框不存在");
        var rows=jdbc.queryForList("SELECT expires_at FROM user_avatar_frame WHERE user_id=? AND frame_id=? FOR UPDATE",user,frame);
        Long membershipEnd=null;
        if(memberFrame)try{membershipEnd=memberships.getObject().requireFrame(user,frame);}catch(BizException ignored){}
        if(!rows.isEmpty()){
            var expiry=dateTime(rows.getFirst().get("expires_at"));
            if(expiry==null)return null;
            long end=expiry.atZone(ZONE).toInstant().toEpochMilli();
            if(end>clock.millis())return membershipEnd==null?end:Math.max(end,membershipEnd);
        }
        if(membershipEnd!=null)return membershipEnd;
        throw new BizException("头像框未获得或已到期");
    }

    public Map<String,Object> history(Long user,int page,int size){
        if(page<1||page>100000||size<1||size>100)throw new BizException("分页参数无效");
        var rows=jdbc.queryForList("SELECT sign_date AS signDate,makeup,reward_json AS rewardJson,claimed_at AS claimedAt FROM user_level_signin WHERE user_id=? ORDER BY sign_date DESC LIMIT ? OFFSET ?",user,size,(page-1)*size);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM user_level_signin WHERE user_id=?",Long.class,user);return Map.of("records",rows,"total",total,"current",page,"pages",(total+size-1)/size);
    }
    public List<Map<String,Object>> recommendations(){
        var rows=jdbc.queryForList("SELECT CAST(id AS CHAR) AS id,title,content,content_blocks AS contentBlocks FROM post WHERE status=1 AND checkin_recommended=TRUE ORDER BY create_time DESC,id DESC LIMIT 1");
        if(rows.isEmpty())return rows;
        rows.addAll(jdbc.queryForList("SELECT CAST(id AS CHAR) AS id,title,content,content_blocks AS contentBlocks FROM post WHERE status=1 AND checkin_recommended=TRUE AND id<>? ORDER BY RAND() LIMIT 2",rows.getFirst().get("id")));
        for(var r:rows){
            var covers=jdbc.queryForList("SELECT COALESCE(NULLIF(cover_url,''),url) FROM post_media WHERE post_id=? ORDER BY sort_order,id LIMIT 1",String.class,r.get("id"));
            r.put("coverUrl",covers.isEmpty()?"":covers.getFirst());
            String text=Objects.toString(r.get("content"),"").replaceAll("<[^>]*>","").replaceAll("\\s+"," ");
            r.put("excerpt",text.substring(0,Math.min(100,text.length())));r.remove("contentBlocks");r.remove("content");
        }
        return rows;
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new BizException("签到数据格式无效");}}
    private static LocalDateTime dateTime(Object value){return value==null?null:value instanceof LocalDateTime t?t:((java.sql.Timestamp)value).toLocalDateTime();}
}
