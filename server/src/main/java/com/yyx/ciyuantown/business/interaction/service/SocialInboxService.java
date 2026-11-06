package com.yyx.ciyuantown.business.interaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.post.entity.Post;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Transactional inbox. Business keys make publication retries and concurrent delivery idempotent. */
@Service
@RequiredArgsConstructor
public class SocialInboxService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private static final Map<String,List<String>> TYPES = Map.of(
        "reply",List.of("reply"), "repost",List.of("repost"), "mention",List.of("mention"),
        "like",List.of("post_like","comment_like"), "follow",List.of("follow"));

    public void deliver(Long recipient, Long actor, String type, Long post, Long comment, String key) {
        if(recipient==null || actor==null || recipient.equals(actor)) return;
        db.update("INSERT INTO social_notification(recipient_id,actor_id,type,post_id,comment_id,business_key) " +
            "SELECT ?,?,?,?,?,? FROM app_user WHERE id=? AND deleted=0 AND status=0 " +
            "ON DUPLICATE KEY UPDATE business_key=VALUES(business_key)", recipient,actor,type,post,comment,key,recipient);
    }

    public void published(Post post) {
        if(!Integer.valueOf(1).equals(post.getStatus())) return;
        int first=db.update("INSERT IGNORE INTO social_publication(post_id) VALUES(?)",post.getId());
        if(first==0)return;
        if(post.getRepostOfPostId()!=null)db.update("UPDATE post SET share_count=share_count+1 WHERE id=?",post.getRepostOfPostId());
        if(post.getContentBlocks()==null)return;
        try {
            Set<Long> recipients=new HashSet<>();
            for(var block:json.readTree(post.getContentBlocks()))for(var run:block.path("runs"))
                if("mention".equals(run.path("type").asText()) && run.path("userId").asLong()>0)recipients.add(run.path("userId").asLong());
            String type=post.getRepostOfPostId()==null?"mention":"repost";
            for(Long recipient:recipients)deliver(recipient,post.getAuthorId(),type,post.getId(),null,"publish:"+post.getId()+":"+recipient);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BizException("帖子正文格式不合法");}
    }

    private List<String> types(String kind) {
        var result=TYPES.get(kind);if(result==null)throw new BizException("通知类型不合法");return result;
    }
    private String where(Long user,String kind,boolean following,List<Object> args) {
        var selected=types(kind);args.add(user);args.addAll(selected);
        return " WHERE n.recipient_id=? AND n.type IN ("+String.join(",",Collections.nCopies(selected.size(),"?"))+")"+
            (following?" AND EXISTS(SELECT 1 FROM user_follow f WHERE f.follower_id=n.recipient_id AND f.followee_id=n.actor_id)":"");
    }
    @Transactional(readOnly=true)
    public Map<String,Object> summary(Long user) {
        var result=new LinkedHashMap<String,Object>();long total=0;
        for(var kind:List.of("reply","repost","mention","like","follow")) {
            var args=new ArrayList<Object>();String filter=where(user,kind,false,args);
            long count=db.queryForObject("SELECT COUNT(*) FROM social_notification n"+filter+" AND n.read_at IS NULL",Long.class,args.toArray());
            result.put(kind,count);total+=count;
        }
        result.put("unreadCount",total);return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> list(Long user,String kind,boolean following,int page,int size) {
        if(page<1||page>100000||size<1||size>50)throw new BizException("分页参数不合法");
        var args=new ArrayList<Object>();String filter=where(user,kind,following,args);
        long total=db.queryForObject("SELECT COUNT(*) FROM social_notification n"+filter,Long.class,args.toArray());
        args.add((page-1)*size);args.add(size);
        var rows=db.queryForList("SELECT CAST(n.id AS CHAR) id,CAST(n.actor_id AS CHAR) actorId,n.type,"+
            "CAST(n.post_id AS CHAR) postId,CAST(n.comment_id AS CHAR) commentId,n.read_at IS NOT NULL isRead,"+
            "DATE_FORMAT(n.create_time,'%Y-%m-%d %H:%i:%s') createTime,"+
            "CASE WHEN u.deleted=1 THEN '已注销用户' ELSE COALESCE(u.nickname,'已注销用户') END nickname,"+
            "CASE WHEN u.deleted=0 THEN COALESCE(u.avatar,'') ELSE '' END avatar,"+
            "COALESCE(NULLIF(TRIM(p.title),''),NULLIF(TRIM(p.content),''),DATE_FORMAT(p.create_time,'%Y-%m-%d %H:%i'),'帖子已删除') postLabel,"+
            "COALESCE((SELECT COALESCE(NULLIF(m.cover_url,''),m.url) FROM post_media m WHERE m.post_id=n.post_id ORDER BY m.sort_order,m.id LIMIT 1),'') coverUrl,"+
            "COALESCE(c.content,'') content,COALESCE(c.sticker_url,'') stickerUrl,"+
            "COALESCE(c.content_type,'text') contentType,"+
            "EXISTS(SELECT 1 FROM user_follow f WHERE f.follower_id=n.recipient_id AND f.followee_id=n.actor_id) following,"+
            "EXISTS(SELECT 1 FROM user_follow f WHERE f.follower_id=n.actor_id AND f.followee_id=n.recipient_id) followedBy"+
            " FROM social_notification n LEFT JOIN app_user u ON u.id=n.actor_id LEFT JOIN post p ON p.id=n.post_id"+
            " LEFT JOIN `comment` c ON c.id=n.comment_id"+filter+" ORDER BY n.create_time DESC,n.id DESC LIMIT ?,?",args.toArray());
        return Map.of("records",rows,"total",total,"current",page,"size",size,"pages",(total+size-1)/size);
    }
    @Transactional
    public void read(Long user,List<String> ids) {
        if(ids==null||ids.isEmpty())return;
        if(ids.size()>100||ids.stream().anyMatch(id->id==null||!id.matches("[1-9][0-9]{0,18}")))throw new BizException("通知ID不合法");
        var args=new ArrayList<Object>();args.add(user);args.addAll(ids);
        db.update("UPDATE social_notification SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE recipient_id=? AND id IN ("+
            String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray());
    }
}
