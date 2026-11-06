package com.yyx.ciyuantown.business.chat;
import com.yyx.ciyuantown.business.notice.service.NoticePushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class ChatPush {
 private final JdbcTemplate db;private final NoticePushService push;private final ChatService chat;private final ChatSettings settings;
 @Scheduled(fixedDelay=5000) public void dispatch(){try{
  if(!settings.offlineReminder().enabled()){
   db.update("UPDATE chat_push_task SET state='cancelled',lease_until=0 WHERE state IN ('pending','sending')");return;
  }
  var config=push.config();if(!config.enabled())return;
  db.update("UPDATE chat_push_task SET state='pending' WHERE state='sending' AND lease_until<?",chat.now());
  for(long id:db.queryForList("SELECT id FROM chat_push_task WHERE state='pending' AND next_attempt_at<=? ORDER BY id LIMIT 20",Long.class,chat.now())){
   if(db.update("UPDATE chat_push_task SET state='sending',attempts=attempts+1,lease_until=? WHERE id=? AND state='pending'",chat.now()+120000,id)!=1)continue;
   var rows=db.queryForList("SELECT t.*,m.conversation_id,m.sender_id,m.kind,m.content,u.nickname FROM chat_push_task t JOIN chat_message m ON m.id=t.message_id JOIN chat_conversation cc ON cc.id=m.conversation_id JOIN app_user u ON u.id=m.sender_id JOIN app_user receiver ON receiver.id=t.user_id JOIN notice_push_device d ON BINARY d.cid=BINARY t.cid AND d.user_id=t.user_id AND d.token_version=t.token_version JOIN chat_member cm ON cm.conversation_id=m.conversation_id AND cm.user_id=t.user_id WHERE t.id=? AND t.state='sending' AND m.id>cc.cleared_through AND m.revoked=FALSE AND m.id>cm.read_message_id AND receiver.status=0 AND (receiver.deleted=FALSE OR receiver.deleted IS NULL) AND COALESCE(receiver.token_version,0)=t.token_version",id);
   if(rows.isEmpty()||!push.config().enabled()||!settings.offlineReminder().enabled()){db.update("UPDATE chat_push_task SET state='cancelled',lease_until=0 WHERE id=?",id);continue;}
   var t=rows.getFirst();try{String content=switch(t.get("kind").toString()){case "video"->"[视频]";case "product"->ChatProductCards.summary(Objects.toString(t.get("content"),""));case "post"->ChatPostCards.summary(Objects.toString(t.get("content"),""));case "image"->"[图片]";case "emoji"->"[表情]";default->t.get("content").toString();};if(content.codePointCount(0,content.length())>45)content=content.substring(0,content.offsetByCodePoints(0,45));
    push.send(config,Map.of("requestId",t.get("request_id"),"cid",t.get("cid"),"title",Objects.toString(t.get("nickname"),"新私聊"),"content",content,"payload",Map.of("type","chat","conversationId",""+t.get("conversation_id"),"messageId",""+t.get("message_id"))));db.update("UPDATE chat_push_task SET state='accepted',lease_until=0 WHERE id=? AND state='sending'",id);
   }catch(Exception e){int attempts=((Number)t.get("attempts")).intValue();db.update("UPDATE chat_push_task SET state=?,lease_until=0,next_attempt_at=?,last_error='私聊推送调用失败' WHERE id=? AND state='sending'",attempts>=5?"failed":"pending",chat.now()+Math.min(3600000,30000L*(1<<Math.min(attempts,6))),id);}
  }
 }catch(Exception e){log.warn("Chat push unavailable: {}",e.getClass().getSimpleName());}}
}
