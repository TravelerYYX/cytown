package com.yyx.ciyuantown.business.chat;

import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class ChatService {
 private final JdbcTemplate db;
 private final ChatSettings settings;
 private final ChatRealtime realtime;
 private final ChatPolicy policy;
 @org.springframework.beans.factory.annotation.Autowired(required=false)
 private ChatPostCards postCards;
 @org.springframework.beans.factory.annotation.Autowired(required=false)
 private com.yyx.ciyuantown.business.shop.ShopCatalog shop;
 @org.springframework.beans.factory.annotation.Autowired(required=false)
 private com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
 public record Asset(String id,String kind,String state,String version,String mime,String extension,long sizeBytes,int width,int height,long durationMs,String coverId,String errorMessage) {}
 public record Reply(String messageId,String senderId,String senderName,long sentAt,String kind,String summary,String status) {}
 public record Message(String id,String conversationId,String senderId,String clientId,String kind,String content,long sentAt,long revokeUntil,boolean revoked,boolean canRevoke,boolean protectedReceipt,Asset asset,boolean deleted,Reply reply,ChatPostCards.Card post,com.yyx.ciyuantown.business.shop.ShopCatalog.Card product) {}
 public record Conversation(String id,String peerId,String name,String iconUrl,String lastMsg,long lastTime,long sortTime,long unread,boolean isTop,boolean isOfficial,String lastMessageId,ChatPolicy.Restriction restriction,String avatarFrame,Long avatarFrameExpiresAt) {}
 public record History(List<Message> records,String nextCursor) {}
 public record Send(String peerId,String clientId,String kind,String content,String assetId,String replyToId,String postId,String productId) {
  public Send(String peerId,String clientId,String kind,String content,String assetId,String replyToId,String postId){this(peerId,clientId,kind,content,assetId,replyToId,postId,null);}
  public Send(String peerId,String clientId,String kind,String content,String assetId){this(peerId,clientId,kind,content,assetId,null,null);}
  public Send(String peerId,String clientId,String kind,String content,String assetId,String replyToId){this(peerId,clientId,kind,content,assetId,replyToId,null);}
 }
 public record Event(long sequence,String kind,String conversationId,String messageId) {}
 public record Sync(List<Event> events,long cursor,boolean more) {}
 public record Operation(String operationId,String state) {}
 public long now(){return System.currentTimeMillis();}
 private static long number(Object o){return o==null?0:((Number)o).longValue();}
 private static boolean flag(Object o){return Boolean.TRUE.equals(o)||o instanceof Number n&&n.intValue()!=0;}
 public static long id(String value){try{long id=Long.parseLong(value);if(id>0)return id;}catch(Exception ignored){}throw new BizException("无效的私聊标识");}
 private void validUser(long user){if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND status=0 AND (deleted=FALSE OR deleted IS NULL)",Integer.class,user)!=1)throw new BizException("用户不存在或已停用");}
 public void lockStreams(long a,long b){
  for(long u:new TreeSet<>(List.of(a,b))){validUser(u);db.update("INSERT INTO chat_stream(user_id) VALUES(?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)",u);db.queryForObject("SELECT sequence_no FROM chat_stream WHERE user_id=? FOR UPDATE",Long.class,u);}
 }
 public void lockAdminStreams(long a,long b){
  for(long u:new TreeSet<>(List.of(a,b))){db.update("INSERT INTO chat_stream(user_id) VALUES(?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)",u);db.queryForObject("SELECT sequence_no FROM chat_stream WHERE user_id=? FOR UPDATE",Long.class,u);}
 }
 public void requireVisible(long conversation,long message){var rows=db.queryForList("SELECT cleared_through FROM chat_conversation WHERE id=? FOR UPDATE",conversation);if(rows.isEmpty()||number(rows.getFirst().get("cleared_through"))>=message)throw new BizException("消息已被管理员删除");}
 public void refreshLast(long conversation){db.update("UPDATE chat_conversation SET last_message_id=(SELECT COALESCE(MAX(m.id),0) FROM chat_message m WHERE m.conversation_id=? AND m.id>chat_conversation.cleared_through) WHERE id=?",conversation,conversation);}
 public long ensure(long a,long b){
  if(a==b)throw new BizException("不能给自己发送私聊");
  long low=Math.min(a,b),high=Math.max(a,b);
  db.update("INSERT IGNORE INTO chat_conversation(user_low,user_high) VALUES(?,?)",low,high);
  long c=db.queryForObject("SELECT id FROM chat_conversation WHERE user_low=? AND user_high=? FOR UPDATE",Long.class,low,high);
  db.update("INSERT IGNORE INTO chat_member(conversation_id,user_id) VALUES(?,?),(?,?)",c,a,c,b);return c;
 }
 public void event(long a,long b,String kind,long conversation,Long message){
  for(long u:new TreeSet<>(List.of(a,b))){
   db.update("UPDATE chat_stream SET sequence_no=sequence_no+1 WHERE user_id=?",u);
   long seq=db.queryForObject("SELECT sequence_no FROM chat_stream WHERE user_id=?",Long.class,u);
   db.update("INSERT INTO chat_event(user_id,sequence_no,kind,conversation_id,message_id) VALUES(?,?,?,?,?)",u,seq,kind,conversation,message);
   realtime.notifyAfterCommit(u);
  }
 }
 @Transactional public void relationship(long a,long b){lockStreams(a,b);long c=ensure(a,b);event(a,b,"relationship",c,null);}
 private boolean follows(long a,long b){return db.queryForObject("SELECT COUNT(*) FROM user_follow WHERE follower_id=? AND followee_id=?",Integer.class,a,b)>0;}
 public long peer(long user,long conversation){
  var rows=db.queryForList("SELECT * FROM chat_conversation WHERE id=? AND (user_low=? OR user_high=?)",conversation,user,user);
  if(rows.isEmpty())throw new BizException("会话不存在");var r=rows.getFirst();long other=number(r.get("user_low"))==user?number(r.get("user_high")):number(r.get("user_low"));
  return other;
 }
 @Transactional public Conversation open(long user,long other){
  lockStreams(user,other);long c=ensure(user,other);policy.requireSend(user,c);return conversation(user,c);
 }
 @Transactional(readOnly=true) public Conversation conversation(long user,long conversation){
  validUser(user);long other=peer(user,conversation);validUser(other);
  return conversationRows(user,conversation).getFirst();
 }
 @Transactional(readOnly=true) public List<Conversation> conversations(long user){return conversationRows(user,null);}
 private List<Conversation> conversationRows(long user,Long only){
  validUser(user);
  var rows=db.queryForList("SELECT c.*,u.nickname,u.avatar,u.avatar_frame,u.avatar_frame_expires_at,f.create_time AS follow_time,m.read_message_id,lm.id AS last_id,lm.sent_at AS last_sent_at,lm.kind AS last_kind,lm.content AS last_content,lm.sender_id AS last_sender_id,lm.revoked AS last_revoked,(SELECT COUNT(*) FROM chat_message unread WHERE unread.conversation_id=c.id AND unread.sender_id<>m.user_id AND unread.id>m.read_message_id AND unread.revoked=FALSE AND unread.id>c.cleared_through) AS unread_count FROM chat_conversation c JOIN chat_member m ON m.conversation_id=c.id AND m.user_id=? JOIN app_user u ON u.id=CASE WHEN c.user_low=? THEN c.user_high ELSE c.user_low END LEFT JOIN user_follow f ON f.follower_id=? AND f.followee_id=u.id LEFT JOIN chat_message lm ON lm.id=c.last_message_id WHERE "+(only==null?"(f.id IS NOT NULL OR c.has_history=TRUE)":"c.id="+only)+" ORDER BY c.id DESC",user,user,user);
  List<Conversation> result=new ArrayList<>();
  for(var r:rows){long c=number(r.get("id")),other=number(r.get("user_low"))==user?number(r.get("user_high")):number(r.get("user_low"));
   List<Map<String,Object>> last=new ArrayList<>();if(r.get("last_id")!=null){Map<String,Object> latest=new HashMap<>();for(String name:List.of("kind","content","sender_id","revoked","sent_at"))latest.put(name,r.get("last_"+name));latest.put("id",r.get("last_id"));last.add(latest);}
   long lastTime=last.isEmpty()?0:number(last.getFirst().get("sent_at"));long sort=lastTime;
   if(sort==0&&r.get("follow_time") instanceof java.util.Date d)sort=d.getTime();
   long unread=number(r.get("unread_count"));
   result.add(new Conversation(""+c,""+other,Objects.toString(r.get("nickname"),"已注销用户"),Objects.toString(r.get("avatar"),""),last.isEmpty()?"暂无消息":summary(last.getFirst(),user),lastTime,sort,unread,false,false,last.isEmpty()?"":""+number(last.getFirst().get("id")),policy.get(user,c),Objects.toString(r.get("avatar_frame"),""),r.get("avatar_frame_expires_at")==null?null:number(r.get("avatar_frame_expires_at"))));
  }
  result.sort(Comparator.comparingLong(Conversation::sortTime).reversed().thenComparing(Conversation::id));return result;
 }
 private String summary(Map<String,Object> m,long user){if(flag(m.get("revoked")))return number(m.get("sender_id"))==user?"你撤销了一条消息":"对方撤销了一条消息";return switch(m.get("kind").toString()){case "image"->"[图片]";case "emoji"->"[表情]";case "video"->"[视频]";case "product"->ChatProductCards.summary(Objects.toString(m.get("content"),""));case "post"->ChatPostCards.summary(Objects.toString(m.get("content"),""));default->m.get("content").toString();};}
 @Transactional(readOnly=true) public History history(long user,long conversation,long before,int count){
  peer(user,conversation);int size=Math.max(1,Math.min(count,100));
  var rows=db.queryForList("SELECT m.* FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id WHERE m.conversation_id=? AND m.id>c.cleared_through AND m.id<? ORDER BY m.id DESC LIMIT ?",conversation,before<=0?Long.MAX_VALUE:before,size+1);
  boolean more=rows.size()>size;if(more)rows.removeLast();var replies=replyTargets(rows);var sharedPosts=sharedPosts(rows);List<Message> result=rows.stream().map(r->view(r,user,replies,sharedPosts)).toList();String cursor=more?result.getLast().id():"";return new History(result,cursor);
 }
 public Asset asset(long id){var r=assetRow(id);return new Asset(""+id,r.get("kind").toString(),r.get("state").toString(),r.get("version").toString(),r.get("mime").toString(),r.get("extension").toString(),number(r.get("size_bytes")),(int)number(r.get("width")),(int)number(r.get("height")),number(r.get("duration_ms")),r.get("cover_id")==null?"":""+number(r.get("cover_id")),r.get("error_message").toString());}
 public Map<String,Object> assetRow(long id){var rows=db.queryForList("SELECT * FROM chat_asset WHERE id=?",id);if(rows.isEmpty())throw new BizException("媒体不存在");return rows.getFirst();}
 public Message message(long user,long id){var rows=db.queryForList("SELECT * FROM chat_message WHERE id=?",id);if(rows.isEmpty()){var deleted=db.queryForList("SELECT * FROM chat_deleted_message WHERE message_id=?",id);if(deleted.isEmpty())throw new BizException("消息不存在");var m=deleted.getFirst();peer(user,number(m.get("conversation_id")));return deleted(id,m);}var m=rows.getFirst();long c=number(m.get("conversation_id"));peer(user,c);if(db.queryForObject("SELECT cleared_through FROM chat_conversation WHERE id=?",Long.class,c)>=id)return deleted(id,m);return view(m,user);}
 private Message deleted(long id,Map<String,Object> m){return new Message(""+id,""+number(m.get("conversation_id")),""+number(m.get("sender_id")),m.get("client_id").toString(),"text","",0,0,true,false,false,null,true,null,null,null);}
 private Map<Long,Map<String,Object>> replyTargets(List<Map<String,Object>> messages){
  var ids=messages.stream().map(m->number(m.get("reply_to_id"))).filter(v->v>0).distinct().toList();
  Map<Long,Map<String,Object>> result=new HashMap<>();if(ids.isEmpty())return result;
  String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
  for(var r:db.queryForList("SELECT m.*,u.nickname AS reply_sender_name,c.cleared_through FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id LEFT JOIN app_user u ON u.id=m.sender_id WHERE m.id IN ("+placeholders+")",ids.toArray()))result.put(number(r.get("id")),r);
  return result;
 }
 private Reply reply(Map<String,Object> message,Map<Long,Map<String,Object>> targets){
  long target=number(message.get("reply_to_id"));if(target==0||flag(message.get("revoked")))return null;
  var m=targets.get(target);
  if(m==null||number(m.get("conversation_id"))!=number(message.get("conversation_id"))||target<=number(m.get("cleared_through")))return new Reply(""+target,"","",0,"text","","deleted");
  boolean revoked=flag(m.get("revoked"));String kind=m.get("kind").toString();
  String summary=revoked?"":switch(kind){case "image"->"[图片]";case "emoji"->"[表情]";case "video"->"[视频]";case "product"->ChatProductCards.summary(Objects.toString(m.get("content"),""));case "post"->ChatPostCards.summary(Objects.toString(m.get("content"),""));default->Objects.toString(m.get("content"),"");};
  if(summary.codePointCount(0,summary.length())>200)summary=summary.substring(0,summary.offsetByCodePoints(0,200));
  return new Reply(""+target,""+number(m.get("sender_id")),Objects.toString(m.get("reply_sender_name"),"已注销用户"),number(m.get("sent_at")),kind,summary,revoked?"revoked":"available");
 }
 Message view(Map<String,Object> m,long user){
  return view(m,user,replyTargets(List.of(m)),sharedPosts(List.of(m)));
 }
 // Legacy snapshots predate author-frame fields. Resolve missing decorations in one
 // query per page without rewriting the stored title, cover, or author identity.
 private Map<Long,ChatPostCards.Card> sharedPosts(List<Map<String,Object>> messages){
  Map<Long,ChatPostCards.Card> cards=new HashMap<>();Set<String> authors=new HashSet<>();
  for(var message:messages){
   if(flag(message.get("revoked"))||!"post".equals(message.get("kind")))continue;
   var card=ChatPostCards.decode(Objects.toString(message.get("content"),""));if(card==null)continue;
   cards.put(number(message.get("id")),card);
   if(card.authorAvatarFrame()==null&&card.authorId()!=null&&card.authorId().matches("[1-9][0-9]{0,18}"))authors.add(card.authorId());
  }
  if(authors.isEmpty())return cards;
  Map<String,Map<String,Object>> frames=new HashMap<>();
  String placeholders=String.join(",",Collections.nCopies(authors.size(),"?"));
  for(var row:db.queryForList("SELECT id,avatar_frame,avatar_frame_expires_at FROM app_user WHERE id IN ("+placeholders+") AND status=0 AND (deleted=FALSE OR deleted IS NULL)",authors.toArray()))frames.put(Objects.toString(row.get("id")),row);
  cards.replaceAll((messageId,card)->{
   var frame=frames.get(card.authorId());if(card.authorAvatarFrame()!=null||frame==null)return card;
   return new ChatPostCards.Card(card.id(),card.title(),card.coverUrl(),card.authorId(),card.authorName(),card.authorAvatar(),card.video(),Objects.toString(frame.get("avatar_frame"),""),frame.get("avatar_frame_expires_at")==null?null:number(frame.get("avatar_frame_expires_at")));
  });
  return cards;
 }
 private Message view(Map<String,Object> m,long user,Map<Long,Map<String,Object>> replies,Map<Long,ChatPostCards.Card> sharedPosts){
  long id=number(m.get("id"));boolean revoked=flag(m.get("revoked")),protectedReceipt=flag(m.get("protected_receipt"));
  boolean pending=db.queryForObject("SELECT COUNT(*) FROM chat_receipt WHERE message_id=? AND state='reserved'",Integer.class,id)>0;
  boolean own=number(m.get("sender_id"))==user;
  return new Message(""+id,""+number(m.get("conversation_id")),""+number(m.get("sender_id")),m.get("client_id").toString(),m.get("kind").toString(),revoked||"post".equals(m.get("kind"))||"product".equals(m.get("kind"))?"":m.get("content").toString(),number(m.get("sent_at")),number(m.get("revoke_until")),revoked,own&&!revoked&&!protectedReceipt&&!pending&&now()<number(m.get("revoke_until")),protectedReceipt,revoked||m.get("asset_id")==null?null:asset(number(m.get("asset_id"))),false,reply(m,replies),sharedPosts.get(id),revoked||!"product".equals(m.get("kind"))?null:ChatProductCards.decode(m.get("content").toString()));
 }
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public Message send(long user,Send p){
  if(p==null||p.clientId()==null||!p.clientId().matches("[a-zA-Z0-9_-]{8,80}"))throw new BizException("发送标识无效");
  if("product".equals(p.kind())&&cleanup!=null)cleanup.lock();
  long other=id(p.peerId());if(user==other)throw new BizException("不能给自己发送消息");lockStreams(user,other);
  validUser(user);validUser(other);
  if(!db.queryForList("SELECT message_id FROM chat_deleted_message WHERE sender_id=? AND client_id=? FOR UPDATE",user,p.clientId()).isEmpty())throw new BizException("该消息已被管理员删除，不能重试发送");
  var duplicate=db.queryForList("SELECT * FROM chat_message WHERE sender_id=? AND client_id=? FOR UPDATE",user,p.clientId());
  if(!duplicate.isEmpty()){var m=duplicate.getFirst();long previous=number(m.get("conversation_id"));if(peer(user,previous)!=other)throw new BizException("发送标识已被使用");requireVisible(previous,number(m.get("id")));return view(m,user);}
  long c=ensure(user,other);db.queryForObject("SELECT id FROM chat_conversation WHERE id=? FOR UPDATE",Long.class,c);
  if(db.queryForObject("SELECT COUNT(*) FROM user_block WHERE owner_id=? AND target_id=?",Integer.class,other,user)>0)throw new BizException("已被对方拉黑");
  policy.requireSend(user,c);
  if(!Set.of("text","image","emoji","video","post","product").contains(p.kind()))throw new BizException("不支持的消息类型");
  String content=p.content()==null?"":p.content();Long media=null;
  if("text".equals(p.kind())){if(content.isBlank()||content.codePointCount(0,content.length())>5000)throw new BizException("请输入1至5000字消息");}
  else if("post".equals(p.kind())){if(postCards==null)throw new BizException("帖子分享服务不可用");content=ChatPostCards.encode(postCards.snapshot(p.postId()));}
  else if("product".equals(p.kind())){if(shop==null)throw new BizException("商品分享服务不可用");content=ChatProductCards.encode(shop.shareCard(p.productId()));}
  else {media=id(p.assetId());var a=assetRow(media);if(number(a.get("owner_id"))!=user||!"ready".equals(a.get("state"))||flag(a.get("attached"))||!p.kind().equals(a.get("kind")))throw new BizException("媒体尚未准备好或不可用");content="";}
  Long replyTo=null;
  if(p.replyToId()!=null&&!p.replyToId().isBlank()){
   if(!"text".equals(p.kind()))throw new BizException("引用后请发送文字回复");replyTo=id(p.replyToId());
   var targets=db.queryForList("SELECT * FROM chat_message WHERE id=? AND conversation_id=? FOR UPDATE",replyTo,c);
   if(targets.isEmpty()||flag(targets.getFirst().get("revoked")))throw new BizException("原消息已撤销或删除，无法引用");requireVisible(c,replyTo);
  }
  long time=now();var values=new HashMap<String,Object>();values.put("conversation_id",c);values.put("sender_id",user);values.put("client_id",p.clientId());values.put("kind",p.kind());values.put("content",content);values.put("asset_id",media);values.put("sent_at",time);values.put("revoke_until",time+settings.get().revokeSeconds()*1000L);values.put("reply_to_id",replyTo);
  long message=new SimpleJdbcInsert(db).withTableName("chat_message").usingColumns("conversation_id","sender_id","client_id","kind","content","asset_id","sent_at","revoke_until","reply_to_id").usingGeneratedKeyColumns("id").executeAndReturnKey(values).longValue();
  db.update("UPDATE chat_conversation SET last_message_id=?,has_history=TRUE WHERE id=?",message,c);
  if(media!=null){db.update("UPDATE chat_asset SET attached=TRUE WHERE id=?",media);db.update("UPDATE chat_asset SET attached=TRUE WHERE id=(SELECT cover_id FROM (SELECT cover_id FROM chat_asset WHERE id=?) a)",media);}
  if(settings.offlineReminder().enabled())db.update("INSERT IGNORE INTO chat_push_task(message_id,user_id,cid,token_version,request_id) SELECT ?,?,d.cid,d.token_version,REPLACE(UUID(),'-','') FROM notice_push_device d JOIN app_user u ON u.id=d.user_id WHERE d.user_id=? AND d.token_version=COALESCE(u.token_version,0)",message,other,other);
  event(user,other,"message",c,message);return message(user,message);
 }
 @Transactional public void read(long user,long conversation,long message){long other=peer(user,conversation);lockStreams(user,other);long maximum=db.queryForObject("SELECT COALESCE(MAX(id),0) FROM chat_message WHERE conversation_id=? AND id<=?",Long.class,conversation,message);int updated=db.update("UPDATE chat_member SET read_message_id=? WHERE conversation_id=? AND user_id=? AND read_message_id<?",maximum,conversation,user,maximum);if(updated>0)event(user,other,"read",conversation,null);}
 private Map<String,Object> lockMessage(long user,long id){var msg=message(user,id);long c=ChatService.id(msg.conversationId()),other=peer(user,c);lockStreams(user,other);requireVisible(c,id);var rows=db.queryForList("SELECT * FROM chat_message WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new BizException("消息已被管理员删除");return rows.getFirst();}
 @Transactional public Message revoke(long user,long id){
  if(cleanup!=null)cleanup.lock();
  var m=lockMessage(user,id);if(number(m.get("sender_id"))!=user)throw new BizException("只能撤销自己的消息");
  if(flag(m.get("revoked")))return view(m,user);
  if(!view(m,user).canRevoke())throw new BizException("消息已超时、对方已保存/接收或操作正在进行");
  db.update("UPDATE chat_message SET revoked=TRUE,content='' WHERE id=?",id);db.update("UPDATE chat_push_task SET state='cancelled' WHERE message_id=? AND state IN ('pending','sending')",id);
  if(m.get("asset_id")!=null){long asset=number(m.get("asset_id"));var a=assetRow(asset);List<Long> retired=new ArrayList<>();retired.add(asset);if(a.get("cover_id")!=null)retired.add(number(a.get("cover_id")));for(long item:retired){if(cleanup!=null)cleanup.track("chat_asset",item);db.update("UPDATE chat_asset SET manifest_json='{\"version\":\"retired\",\"size\":0,\"parts\":[]}',state='retired' WHERE id=?",item);}}
  long c=number(m.get("conversation_id"));event(user,peer(user,c),"revoked",c,id);return message(user,id);
 }
 @Transactional public Operation operation(long user,long message,String operation,String kind,String action){
  if(operation==null||!operation.matches("[a-zA-Z0-9_-]{8,80}"))throw new BizException("操作标识无效");
  var m=lockMessage(user,message);long c=number(m.get("conversation_id")),sender=number(m.get("sender_id"));
  if(sender==user)throw new BizException("仅接收方需要提交回执");
  if(!Set.of("reserve","confirm","fail").contains(action))throw new BizException("无效的回执操作");
  boolean correct="saved".equals(kind)&&Set.of("image","emoji").contains(m.get("kind"))||"received".equals(kind)&&"video".equals(m.get("kind"));if(!correct)throw new BizException("回执类型不匹配");
  var rows=db.queryForList("SELECT * FROM chat_receipt WHERE operation_id=?",operation);
  if(!rows.isEmpty()){var op=rows.getFirst();if(number(op.get("message_id"))!=message||number(op.get("user_id"))!=user||!kind.equals(op.get("kind")))throw new BizException("操作标识已被使用");String state=op.get("state").toString();if(!"reserved".equals(state)||"reserve".equals(action))return new Operation(operation,state);}
  else {if("fail".equals(action))return new Operation(operation,"failed");if(!"reserve".equals(action))throw new BizException("请先申请操作");if(flag(m.get("revoked")))throw new BizException("消息已撤销");db.update("INSERT INTO chat_receipt(operation_id,message_id,user_id,kind,created_at) VALUES(?,?,?,?,?)",operation,message,user,kind,now());}
  String state=switch(action){case "confirm"->"confirmed";case "fail"->"failed";default->"reserved";};
  db.update("UPDATE chat_receipt SET state=? WHERE operation_id=?",state,operation);
  if("confirmed".equals(state))db.update("UPDATE chat_message SET protected_receipt=TRUE WHERE id=?",message);
  event(user,sender,"receipt",c,message);return new Operation(operation,state);
 }
 @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000) public void expireTickets(){db.update("DELETE FROM chat_ticket WHERE expires_at<? LIMIT 1000",now());}
 @Transactional public String ticket(long user,int version){validUser(user);String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");db.update("INSERT INTO chat_ticket(ticket,user_id,token_version,expires_at) VALUES(?,?,?,?)",token,user,version,now()+30000);return token;}
 @Transactional(readOnly=true) public Sync sync(long user,long cursor){var events=db.query("SELECT * FROM chat_event WHERE user_id=? AND sequence_no>? ORDER BY sequence_no LIMIT 201",(r,i)->new Event(r.getLong("sequence_no"),r.getString("kind"),""+r.getLong("conversation_id"),r.getObject("message_id")==null?"":""+r.getLong("message_id")),user,Math.max(0,cursor));boolean more=events.size()>200;if(more)events.removeLast();return new Sync(events,events.isEmpty()?cursor:events.getLast().sequence(),more);}
}
