package com.yyx.ciyuantown.business.chat;

import com.yyx.ciyuantown.business.appuser.service.LoginAccountService;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.ResourceCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class ChatAdministration {
    private final JdbcTemplate db;
    private final ChatService chat;
    private final ChatPolicy policy;
    private final LoginAccountService accounts;
    private final ResourceCleanupService cleanup;
    private final PlatformTransactionManager transactions;
    private final com.yyx.ciyuantown.business.appuser.service.AppUserService users;
    public record Person(String id,String account,String nickname,String avatar,int status,boolean deleted,ChatPolicy.Restriction restriction) {}
    public record Conversation(String id,Person a,Person b,String lastMessage,long lastTime) {}
    public record Page(List<Conversation> records,long total,int pageNum,int pageSize) {}
    public record History(List<ChatService.Message> records,String older,String newer) {}
    public record Job(String id,String conversationId,String state,long remaining,String errorMessage) {}
    public record Rule(String userId,String conversationId,String action,Long minutes) {}
    static long n(Object value){return value==null?0:((Number)value).longValue();}
    public void require(Long operator){accounts.requireSuper(operator);}
    Map<String,Object> conversation(long id){var rows=db.queryForList("SELECT * FROM chat_conversation WHERE id=?",id);if(rows.isEmpty())throw new BizException("会话不存在");return rows.getFirst();}
    private Person person(long id,long conversation){
        var rows=db.queryForList("SELECT u.id,u.nickname,u.avatar,u.status,u.deleted,COALESCE(a.username,u.username,u.qq_number,'') AS account FROM app_user u LEFT JOIN admin_user a ON a.id=u.official_admin_id WHERE u.id=?",id);
        if(rows.isEmpty())return new Person(""+id,"","已注销用户","",1,true,policy.get(id,conversation));
        var r=rows.getFirst();return new Person(""+id,Objects.toString(r.get("account"),""),Objects.toString(r.get("nickname"),"已注销用户"),Objects.toString(r.get("avatar"),""),(int)n(r.get("status")),Boolean.TRUE.equals(r.get("deleted")),policy.get(id,conversation));
    }
    private String summary(Map<String,Object> r){if(Boolean.TRUE.equals(r.get("revoked")))return "消息已撤销";return switch(Objects.toString(r.get("kind"),"")){case "image"->"[图片]";case "emoji"->"[表情]";case "video"->"[视频]";case "product"->ChatProductCards.summary(Objects.toString(r.get("content"),""));case "post"->ChatPostCards.summary(Objects.toString(r.get("content"),""));default->Objects.toString(r.get("content"),"");};}
    @Transactional(readOnly=true) public Page list(Long operator,String keyword,int page,int size){
        require(operator);if(page<1||size<1||size>100)throw new BizException("分页参数无效");
        String search=keyword==null?"":keyword.trim();if(search.length()>100)throw new BizException("搜索关键词过长");
        String where=" FROM chat_conversation c WHERE c.last_message_id>0";
        List<Object> args=new ArrayList<>();
        if(!search.isEmpty()){
            where+=" AND EXISTS(SELECT 1 FROM app_user u LEFT JOIN admin_user a ON a.id=u.official_admin_id WHERE (u.id=c.user_low OR u.id=c.user_high) AND (u.nickname LIKE ? OR COALESCE(a.username,u.username,u.qq_number,'') LIKE ?))";
            args.add("%"+search+"%");args.add("%"+search+"%");
        }
        long total=db.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());
        args.add(size);args.add((long)(page-1)*size);
        var rows=db.queryForList("SELECT c.*"+where+" ORDER BY c.last_message_id DESC,c.id DESC LIMIT ? OFFSET ?",args.toArray());
        List<Conversation> result=new ArrayList<>();
        for(var c:rows){long id=n(c.get("id"));var last=db.queryForList("SELECT kind,content,revoked,sent_at FROM chat_message WHERE id=?",n(c.get("last_message_id")));var m=last.isEmpty()?Map.<String,Object>of():last.getFirst();result.add(new Conversation(""+id,person(n(c.get("user_low")),id),person(n(c.get("user_high")),id),summary(m),n(m.get("sent_at"))));}
        return new Page(result,total,page,size);
    }
    @Transactional(readOnly=true) public Conversation detail(Long operator,long id){
        require(operator);var c=conversation(id);return new Conversation(""+id,person(n(c.get("user_low")),id),person(n(c.get("user_high")),id),"",0);
    }
    @Transactional(readOnly=true) public History history(Long operator,long id,long before,long after,int size){
        require(operator);conversation(id);if(size<1||size>100||before<0||after<0||before>0&&after>0)throw new BizException("分页参数无效");
        String direction=after>0?"ASC":"DESC";
        var rows=db.queryForList("SELECT m.* FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id WHERE m.conversation_id=? AND m.id>c.cleared_through AND m.id"+(after>0?">":"<")+"? ORDER BY m.id "+direction+" LIMIT ?",id,after>0?after:before>0?before:Long.MAX_VALUE,size);
        if(after==0)Collections.reverse(rows);
        if(rows.isEmpty())return new History(List.of(),"","");
        long low=n(rows.getFirst().get("id")),high=n(rows.getLast().get("id"));
        boolean older=db.queryForObject("SELECT COUNT(*) FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id WHERE c.id=? AND m.id>c.cleared_through AND m.id<?",Long.class,id,low)>0;
        boolean newer=db.queryForObject("SELECT COUNT(*) FROM chat_message WHERE conversation_id=? AND id>?",Long.class,id,high)>0;
        return new History(rows.stream().map(r->chat.view(r,0)).toList(),older?""+low:"",newer?""+high:"");
    }
    public void assetAccess(Long operator,long asset){
        require(operator);
        if(db.queryForObject("SELECT COUNT(*) FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id LEFT JOIN chat_asset a ON a.id=m.asset_id WHERE m.id>c.cleared_through AND m.revoked=FALSE AND (m.asset_id=? OR a.cover_id=?)",Integer.class,asset,asset)==0)throw new BizException("媒体已删除或不可访问");
    }
    private void audit(long operator,String action,long conversation,String target,String detail){db.update("INSERT INTO chat_admin_audit(operator_id,action,conversation_id,target_id,created_at,detail) VALUES(?,?,?,?,?,?)",operator,action,conversation,target,chat.now(),detail);}
    @Transactional public void status(Long operator,long user,int status){require(operator);chat.lockAdminStreams(user,user);users.updateStatus(user,status);audit(operator,"account-status",0,""+user,"status="+status);chat.event(user,user,"restriction",0,null);}
    @Transactional public void rule(Long operator,Rule input){
        require(operator);if(input==null)throw new BizException("参数不能为空");long user=ChatService.id(input.userId()),scope=input.conversationId()==null||input.conversationId().equals("0")?0:ChatService.id(input.conversationId());
        if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND (deleted=FALSE OR deleted IS NULL)",Integer.class,user)!=1)throw new BizException("用户不存在或已注销");
        long peer=user;if(scope>0){var c=conversation(scope);if(user!=n(c.get("user_low"))&&user!=n(c.get("user_high")))throw new BizException("目标用户不属于该会话");peer=user==n(c.get("user_low"))?n(c.get("user_high")):n(c.get("user_low"));}
        chat.lockAdminStreams(user,peer);
        db.update("INSERT IGNORE INTO chat_restriction(user_id,conversation_id,operator_id,updated_at) VALUES(?,?,?,?)",user,scope,operator,chat.now());
        switch(Objects.toString(input.action(),"")){
            case "block","unblock"->{if(scope!=0)throw new BizException("永久私聊封禁仅支持全部私聊");db.update("UPDATE chat_restriction SET blocked=? WHERE user_id=? AND conversation_id=0",input.action().equals("block"),user);}
            case "mute"->{if(input.minutes()==null||input.minutes()<1||input.minutes()>(Long.MAX_VALUE-chat.now())/60000)throw new BizException("请输入有效的正整数分钟数");db.update("UPDATE chat_restriction SET muted_until=? WHERE user_id=? AND conversation_id=?",chat.now()+input.minutes()*60000,user,scope);}
            case "unmute"->db.update("UPDATE chat_restriction SET muted_until=0 WHERE user_id=? AND conversation_id=?",user,scope);
            default->throw new BizException("无效的限制操作");
        }
        db.update("UPDATE chat_restriction SET operator_id=?,updated_at=? WHERE user_id=? AND conversation_id=?",operator,chat.now(),user,scope);
        audit(operator,input.action(),scope,""+user,"minutes="+input.minutes());chat.event(user,user,"restriction",scope,null);
    }
    @Transactional public Job delete(Long operator,long id,Long message){
        require(operator);cleanup.lock();var c=conversation(id);chat.lockAdminStreams(n(c.get("user_low")),n(c.get("user_high")));
        c=db.queryForMap("SELECT * FROM chat_conversation WHERE id=? FOR UPDATE",id);String job=UUID.randomUUID().toString();long through;
        if(message!=null){
            var existing=db.queryForList("SELECT j.id FROM chat_delete_job j WHERE conversation_id=? AND single_id=? ORDER BY created_at DESC LIMIT 1 FOR UPDATE",id,message);
            if(!existing.isEmpty())return job(operator,existing.getFirst().get("id").toString());
            if(db.queryForObject("SELECT COUNT(*) FROM chat_message WHERE id=? AND conversation_id=? AND id>?",Integer.class,message,id,n(c.get("cleared_through")))==0)throw new BizException("消息已删除或不属于该会话");through=message;
        }else {var latest=db.queryForList("SELECT id FROM chat_message WHERE conversation_id=? ORDER BY id DESC LIMIT 1 FOR UPDATE",Long.class,id);through=latest.isEmpty()?0:latest.getFirst();}
        db.update("INSERT INTO chat_delete_job(id,conversation_id,through_id,single_id,operator_id,created_at) VALUES(?,?,?,?,?,?)",job,id,through,message,operator,chat.now());
        if(message==null){
            db.update("UPDATE chat_conversation SET cleared_through=GREATEST(cleared_through,?),last_message_id=0 WHERE id=?",through,id);
            db.update("UPDATE chat_push_task SET state='cancelled' WHERE message_id IN (SELECT id FROM chat_message WHERE conversation_id=? AND id<=?)",id,through);
            chat.event(n(c.get("user_low")),n(c.get("user_high")),"cleared",id,through);
        }else erase(job,db.queryForMap("SELECT * FROM chat_message WHERE id=? FOR UPDATE",message),c,true);
        if(message!=null)db.update("UPDATE chat_delete_job SET rows_done=TRUE WHERE id=?",job);
        chat.refreshLast(id);audit(operator,message==null?"clear":"delete",id,message==null?""+through:""+message,"job="+job);
        return job(operator,job);
    }
    private void erase(String job,Map<String,Object> message,Map<String,Object> conversation,boolean individual){
        long id=n(message.get("id")),c=n(message.get("conversation_id"));
        db.update("INSERT IGNORE INTO chat_deleted_message(message_id,conversation_id,sender_id,client_id) VALUES(?,?,?,?)",id,c,message.get("sender_id"),message.get("client_id"));
        if(message.get("asset_id")!=null){long asset=n(message.get("asset_id"));var assets=db.queryForList("SELECT id,cover_id FROM chat_asset WHERE id=?",asset);List<Long> ids=new ArrayList<>();ids.add(asset);if(!assets.isEmpty()&&assets.getFirst().get("cover_id")!=null)ids.add(n(assets.getFirst().get("cover_id")));for(long item:ids){cleanup.forceRetireChat(item,job);db.update("DELETE FROM chat_video_job WHERE asset_id=?",item);db.update("DELETE FROM chat_asset WHERE id=?",item);}}
        db.update("DELETE FROM chat_receipt WHERE message_id=?",id);db.update("DELETE FROM chat_push_task WHERE message_id=?",id);db.update("DELETE FROM chat_message WHERE id=?",id);
        // One clear event already covers text; media events also retire legacy device-only caches.
        if(individual||message.get("asset_id")!=null)chat.event(n(conversation.get("user_low")),n(conversation.get("user_high")),"deleted",c,id);
    }
    public Job job(Long operator,String id){
        require(operator);var rows=db.queryForList("SELECT * FROM chat_delete_job WHERE id=?",id);if(rows.isEmpty())throw new BizException("删除任务不存在");var row=rows.getFirst();
        long remaining=db.queryForObject("SELECT COUNT(*) FROM chat_delete_object o JOIN media_cleanup_task t ON t.id=o.cleanup_id WHERE o.job_id=? AND t.completed=FALSE",Long.class,id);
        long failed=db.queryForObject("SELECT COUNT(*) FROM chat_delete_object o JOIN media_cleanup_task t ON t.id=o.cleanup_id WHERE o.job_id=? AND t.completed=FALSE AND t.error_message<>''",Long.class,id);
        boolean done=Boolean.TRUE.equals(row.get("rows_done"));String error=Objects.toString(row.get("error_message"),"");
        return new Job(id,""+n(row.get("conversation_id")),!error.isEmpty()||failed>0?"retrying":done&&remaining==0?"completed":"cleaning",remaining,error.isEmpty()&&failed>0?"云端文件清理失败，正在自动重试":error);
    }
    public List<Job> jobs(Long operator,long conversation){require(operator);return db.queryForList("SELECT id FROM chat_delete_job WHERE conversation_id=? ORDER BY created_at DESC LIMIT 20",String.class,conversation).stream().map(id->job(operator,id)).toList();}
    public void process(String id){
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            cleanup.lock();var jobs=db.queryForList("SELECT * FROM chat_delete_job WHERE id=? AND rows_done=FALSE FOR UPDATE",id);if(jobs.isEmpty())return;var job=jobs.getFirst();long c=n(job.get("conversation_id"));var conversation=conversation(c);chat.lockAdminStreams(n(conversation.get("user_low")),n(conversation.get("user_high")));
            var rows=db.queryForList("SELECT * FROM chat_message WHERE conversation_id=? AND id<=? ORDER BY id LIMIT 100 FOR UPDATE",c,n(job.get("through_id")));
            for(var message:rows)erase(id,message,conversation,false);
            db.update("UPDATE chat_delete_job SET rows_done=?,error_message='' WHERE id=?",rows.size()<100,id);chat.refreshLast(c);
        });
    }
    @Scheduled(fixedDelay=1000) public void dispatch(){
        try{for(String id:db.queryForList("SELECT id FROM chat_delete_job WHERE rows_done=FALSE ORDER BY created_at LIMIT 2",String.class)){try{process(id);}catch(Exception e){db.update("UPDATE chat_delete_job SET error_message='聊天记录清理失败，正在自动重试' WHERE id=?",id);log.warn("Chat deletion deferred: job={}, type={}",id,e.getClass().getSimpleName());}}}catch(Exception e){log.warn("Chat deletion queue unavailable: {}",e.getClass().getSimpleName());}
    }
}
