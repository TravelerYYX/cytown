package com.yyx.ciyuantown.business.membership;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.appuser.service.AppUserAccessService;
import com.yyx.ciyuantown.business.level.LevelService;
import com.yyx.ciyuantown.business.checkin.CheckinService;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class MembershipStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
    private final AppUserAccessService access;
    private final LevelService levels;
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZONE);}
    public record Order(String id,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long userId,String planId,MembershipConfig.Plan plan,String profileId,String channel,long amountFen,
        String status,String tradeNo,String errorMessage,LocalDateTime createdAt,LocalDateTime expiresAt,LocalDateTime paidAt,LocalDateTime grantedAt,LocalDateTime grantStart,LocalDateTime grantEnd) {}
    public record Member(boolean active,String planId,MembershipConfig.Plan plan,Long expiresAt) {}
    public record Create(String planId,long version,String channel,String requestKey,boolean agreed) {}
    public MembershipConfig config(){var rows=db.queryForList("SELECT config_json FROM membership_config WHERE id=1",String.class);
        return rows.isEmpty()?MembershipConfig.defaults():decode(rows.getFirst(),MembershipConfig.class);}
    @Transactional @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public MembershipConfig save(MembershipConfig c){cleanup.track("membership_config",1L);c.validate();
        db.update("INSERT IGNORE INTO membership_config(id,version,config_json) VALUES(1,1,?)",encode(MembershipConfig.defaults()));
        long version=db.queryForObject("SELECT version FROM membership_config WHERE id=1 FOR UPDATE",Long.class);
        if(c.version()!=version)throw new BizException("会员配置已更新，请重新加载");
        // Retain removed plans internally so existing memberships, frames and pending orders keep their rights.
        var old=config();var plans=new ArrayList<>(c.plans());
        for(var p:old.plans()){
            if(p.deleted())plans.removeIf(n->n.id().equals(p.id()));
            if(p.deleted()||plans.stream().noneMatch(n->n.id().equals(p.id())))plans.add(p.removed());
        }
        var next=new MembershipConfig(version+1,c.selling(),c.notice(),c.contact(),c.agreement(),plans);next.validate();
        db.update("UPDATE membership_config SET version=?,config_json=? WHERE id=1",next.version(),encode(next));return next;}
    public Member member(Long user){var rows=db.queryForList("SELECT plan_id,expires_at FROM user_membership WHERE user_id=?",user);
        if(rows.isEmpty())return new Member(false,"",null,null);var r=rows.getFirst();var expiry=date(r.get("expires_at"));String id=(String)r.get("plan_id");
        var plan=config().plans().stream().filter(p->p.id().equals(id)).findFirst().orElse(null);
        return new Member(expiry.isAfter(now()),id,plan,expiry.atZone(ZONE).toInstant().toEpochMilli());}
    public Map<String,Member> summaries(List<Long> ids){
        if(ids.size()>100||ids.stream().anyMatch(Objects::isNull))throw new BizException("一次最多查询100位用户");
        var out=new LinkedHashMap<String,Member>();if(ids.isEmpty())return out;
        var unique=new LinkedHashSet<>(ids);var plans=new HashMap<String,MembershipConfig.Plan>();for(var p:config().plans())plans.put(p.id(),p);
        var args=new ArrayList<Object>(unique);args.add(now());
        var rows=db.queryForList("SELECT m.user_id,m.plan_id,m.expires_at FROM user_membership m JOIN app_user u ON u.id=m.user_id WHERE u.deleted=0 AND u.status=0 AND m.user_id IN ("+String.join(",",Collections.nCopies(unique.size(),"?"))+") AND m.expires_at>?",args.toArray());
        for(var r:rows){String id=r.get("plan_id").toString();out.put(r.get("user_id").toString(),new Member(true,id,plans.get(id),date(r.get("expires_at")).atZone(ZONE).toInstant().toEpochMilli()));}return out;
    }
    public List<Order> pending(Long user){return orders("WHERE user_id=? AND status IN ('PENDING','PAID') ORDER BY created_at",user);}
    @Transactional public Order create(Long user,Create c,String profileId){
        cleanup.lock();
        access.requireForUpdate(user);
        if(c==null||c.planId()==null||c.requestKey()==null||!c.requestKey().matches("[A-Za-z0-9-]{8,80}")||!Set.of("APP","H5").contains(c.channel())||!c.agreed())throw new BizException("购买参数无效，请阅读并勾选协议");
        var existing=orders("WHERE user_id=? AND request_key=?",user,c.requestKey());
        if(!existing.isEmpty()){var o=existing.getFirst();if(!o.planId().equals(c.planId())||!o.channel().equals(c.channel()))throw new BizException("购买请求标识已使用");return o;}
        if(!pending(user).isEmpty())throw new BizException("有待确认的会员订单，请先查看我的订阅");
        var config=config();if(config.version()!=c.version())throw new BizException("套餐已更新，请刷新后确认价格");
        var plan=config.plans().stream().filter(p->p.id().equals(c.planId())).findFirst().orElseThrow(()->new BizException("套餐不存在"));
        if(!config.selling()||!plan.selling()||plan.archived()||config.agreement().isBlank())throw new BizException("该会员套餐已停止购买");
        var member=member(user);if(member.active()&&!member.planId().equals(plan.id()))throw new BizException("有效期内只能续购当前档位");
        String id="M"+UUID.randomUUID().toString().replace("-","");var now=now();
        db.update("INSERT INTO membership_order(id,user_id,request_key,plan_id,plan_json,agreement_text,profile_id,channel,amount_fen,status,created_at,expires_at,next_check_at) VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?,?,?)",
            id,user,c.requestKey(),plan.id(),encode(plan),config.agreement(),profileId,c.channel(),plan.priceFen(),now,now.plusMinutes(30),now.plusSeconds(30));return order(id);}
    public Order order(String id){return orders("WHERE id=?",id).stream().findFirst().orElseThrow(()->new BizException("订单不存在"));}
    public Order owned(Long user,String id){var o=order(id);if(!o.userId().equals(user))throw new BizException("无权访问此订单");return o;}
    private void lockUser(Long id){db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",id);}
    @Transactional public void paid(String id,String trade,long fen){var initial=order(id);lockUser(initial.userId());var o=orders("WHERE id=? FOR UPDATE",id).getFirst();
        if(fen!=o.amountFen()||trade==null||trade.isBlank()||trade.length()>80)throw new BizException("支付金额或交易号不匹配");
        if(o.tradeNo()!=null&&!o.tradeNo().equals(trade))throw new BizException("订单交易号不匹配");
        if("ACTIVE".equals(o.status())||"PAID".equals(o.status()))return;
        if("CLOSED".equals(o.status()))throw new BizException("已关闭订单出现付款，须人工核对");
        db.update("UPDATE membership_order SET status='PAID',trade_no=?,paid_at=?,next_check_at=? WHERE id=?",trade,now(),now(),id);}
    @Transactional @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public void fulfill(String id){var initial=order(id);lockUser(initial.userId());var o=orders("WHERE id=? FOR UPDATE",id).getFirst();if(!"PAID".equals(o.status()))return;
        var rows=db.queryForList("SELECT id,deleted,status,coin_balance FROM app_user WHERE id=?",o.userId());
        if(rows.isEmpty()||integer(rows.getFirst().get("deleted"))!=0||integer(rows.getFirst().get("status"))!=0)throw new BizException("付款用户不可用，等待人工处理");
        var member=member(o.userId());if(member.active()&&!member.planId().equals(o.planId()))throw new BizException("会员档位冲突，等待人工处理");
        LocalDateTime start=member.active()?LocalDateTime.ofInstant(Instant.ofEpochMilli(member.expiresAt()),ZONE):now();var end=start.plusMonths(1);
        var balance=new BigDecimal(rows.getFirst().get("coin_balance").toString());if(balance.add(o.plan().coins()).compareTo(CheckinService.MAX_COINS)>0)throw new BizException("次元币余额已达上限，奖励等待处理");
        db.update("INSERT INTO user_membership(user_id,plan_id,expires_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE plan_id=VALUES(plan_id),expires_at=VALUES(expires_at)",o.userId(),o.planId(),end);
        String frame="mem-"+o.planId();
        db.update("INSERT INTO membership_frame_grant(user_id,frame_id,expires_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at)",o.userId(),frame,end);
        var grants=db.queryForList("SELECT expires_at FROM user_avatar_frame WHERE user_id=? AND frame_id=?",o.userId(),frame);
        Long frameEnd=end.atZone(ZONE).toInstant().toEpochMilli();if(!grants.isEmpty()){var other=date(grants.getFirst().get("expires_at"));frameEnd=other==null?null:Math.max(frameEnd,other.atZone(ZONE).toInstant().toEpochMilli());}
        db.update("UPDATE app_user SET avatar_frame_expires_at=? WHERE id=? AND avatar_frame=?",frameEnd,o.userId(),frame);
        levels.awardMembership(o.userId(),o.id(),o.plan().experience());
        if(o.plan().coins().signum()>0){db.update("UPDATE app_user SET coin_balance=coin_balance+? WHERE id=?",o.plan().coins(),o.userId());
            db.update("INSERT INTO user_coin_ledger(user_id,event_key,amount,balance_after) VALUES(?,?,?,?)",o.userId(),"membership:"+o.id(),o.plan().coins(),balance.add(o.plan().coins()));}
        db.update("UPDATE membership_order SET status='ACTIVE',granted_at=?,grant_start=?,grant_end=?,error_message='' WHERE id=?",now(),start,end,id);}
    @Transactional public void closed(String id){var o=order(id);lockUser(o.userId());db.update("UPDATE membership_order SET status='CLOSED',error_message='' WHERE id=? AND status='PENDING'",id);}
    public void retry(String id,String message){db.update("UPDATE membership_order SET error_message=?,attempts=attempts+1,next_check_at=? WHERE id=? AND status IN ('PENDING','PAID')",message,now().plusMinutes(2),id);}
    public boolean claimCheck(String id){return db.update("UPDATE membership_order SET next_check_at=? WHERE id=? AND status='PENDING' AND next_check_at<=?",now().plusSeconds(30),id,now())==1;}
    public List<Order> due(){return orders("WHERE status IN ('PENDING','PAID') AND next_check_at<=? ORDER BY next_check_at LIMIT 30",now());}
    public Map<String,Object> page(Long user,int page,int size,String status,String search){if(page<1||page>100000||size<1||size>100)throw new BizException("分页参数无效");
        var args=new ArrayList<Object>();String where=" WHERE 1=1";if(user!=null){where+=" AND user_id=?";args.add(user);}if(status!=null&&!status.isBlank()){if(!Set.of("PENDING","PAID","ACTIVE","CLOSED").contains(status))throw new BizException("状态无效");where+=" AND status=?";args.add(status);}
        if(search!=null&&!search.isBlank()){where+=" AND (id=? OR CAST(user_id AS CHAR)=? OR trade_no=?)";args.add(search);args.add(search);args.add(search);}
        long total=db.queryForObject("SELECT COUNT(*) FROM membership_order"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        return Map.of("records",orders(where+" ORDER BY created_at DESC LIMIT ? OFFSET ?",args.toArray()),"total",total);}
    private List<Order> orders(String where,Object...args){return db.query("SELECT * FROM membership_order "+where,(r,n)->new Order(r.getString("id"),r.getLong("user_id"),r.getString("plan_id"),decode(r.getString("plan_json"),MembershipConfig.Plan.class),r.getString("profile_id"),r.getString("channel"),r.getLong("amount_fen"),r.getString("status"),r.getString("trade_no"),r.getString("error_message"),date(r.getObject("created_at")),date(r.getObject("expires_at")),date(r.getObject("paid_at")),date(r.getObject("granted_at")),date(r.getObject("grant_start")),date(r.getObject("grant_end"))),args);}
    public List<Map<String,Object>> frames(){return config().plans().stream().filter(p->!p.frameUrl().isBlank()).map(p->{Map<String,Object> m=new LinkedHashMap<>();m.put("id","mem-"+p.id());m.put("name",p.name()+" · 会员专属");m.put("image",Map.of("url",p.frameUrl(),"originalBytes",0,"processedBytes",0));return m;}).toList();}
    public List<Map<String,Object>> ownedFrames(Long user){return db.query("SELECT frame_id,expires_at FROM membership_frame_grant WHERE user_id=? AND expires_at>?",(r,n)->Map.<String,Object>of("frameId",r.getString(1),"expiresAt",date(r.getObject(2)).atZone(ZONE).toInstant().toEpochMilli()),user,now());}
    public Long requireFrame(Long user,String frame){if(config().plans().stream().noneMatch(p->("mem-"+p.id()).equals(frame)))throw new BizException("会员头像框不存在");
        var ends=db.queryForList("SELECT expires_at FROM membership_frame_grant WHERE user_id=? AND frame_id=? AND expires_at>?",java.sql.Timestamp.class,user,frame,now());
        if(ends.isEmpty())throw new BizException("会员头像框未获得或已到期");return ends.getFirst().toLocalDateTime().atZone(ZONE).toInstant().toEpochMilli();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new BizException("会员数据格式错误");}}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new BizException("会员数据读取失败");}}
    private static int integer(Object value){return value instanceof Boolean b?(b?1:0):((Number)value).intValue();}
    private static LocalDateTime date(Object value){return value==null?null:value instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)value;}
}
