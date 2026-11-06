package com.yyx.ciyuantown.business.chat;

import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class ChatPolicy {
    private final JdbcTemplate db;
    public record Restriction(boolean blocked,long globalMutedUntil,long conversationMutedUntil) {
        public long mutedUntil(){ return Math.max(globalMutedUntil,conversationMutedUntil); }
    }
    public Restriction get(long user,long conversation){return get(user,conversation,false);}
    private Restriction get(long user,long conversation,boolean locking){
        boolean blocked=false;long global=0,pair=0;
        for(var row:db.queryForList("SELECT * FROM chat_restriction WHERE user_id=? AND (conversation_id=0 OR conversation_id=?)"+(locking?" FOR UPDATE":""),user,conversation)){
            long scope=((Number)row.get("conversation_id")).longValue();
            long until=((Number)row.get("muted_until")).longValue();
            if(scope==0){blocked=Boolean.TRUE.equals(row.get("blocked"))||row.get("blocked") instanceof Number n&&n.intValue()!=0;global=until;}
            else pair=until;
        }
        return new Restriction(blocked,global,pair);
    }
    public void requireSend(long user,long conversation){
        var rule=get(user,conversation,true);
        if(rule.blocked())throw new BizException("你的私聊发送功能已被封禁");
        if(rule.mutedUntil()>System.currentTimeMillis())throw new BizException("你已被私聊禁言，结束时间："+java.time.Instant.ofEpochMilli(rule.mutedUntil()).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime());
    }
}
