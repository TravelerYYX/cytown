package com.yyx.ciyuantown.business.notice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.notice.dto.*;
import com.yyx.ciyuantown.business.notice.entity.NoticeCategory;
import com.yyx.ciyuantown.business.notice.vo.NoticeListVO;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Publication snapshots, visibility and reads share the same authorization predicate. */
@Service @RequiredArgsConstructor
public class NoticeDeliveryService {
 private final JdbcTemplate db;
 private final NoticePushService push;
 public record Audience(String type,List<Long> userIds) {}
 public record CategorySummary(Long id,String name,String iconUrl,long unreadCount,String latestTitle,String latestTime) {}
 public record Summary(long unreadCount,List<CategorySummary> categories) {}
 public static class NoticePage extends Page<NoticeListVO> {
  private final List<Long> readPublicationIds;
  public NoticePage(int page,int size,long total,List<Long> ids){super(page,size,total);readPublicationIds=ids;}
  public List<Long> getReadPublicationIds(){return readPublicationIds;}
 }
 public List<Long> categoryRecipients(Long id){return db.queryForList("SELECT user_id FROM notice_category_recipient WHERE category_id=? ORDER BY user_id",Long.class,id);}
 public void validatePolicy(NoticeCategoryParam p){
  if(!Set.of("all","users","choose").contains(Objects.toString(p.getRecipientPolicy(),"")))throw new BizException("接收规则不合法");
  if("users".equals(p.getRecipientPolicy()))validateUsers(p.getRecipientUserIds());
 }
 public void saveCategoryRecipients(Long id,NoticeCategoryParam p){
  db.update("DELETE FROM notice_category_recipient WHERE category_id=?",id);
  if("users".equals(p.getRecipientPolicy()))for(Long user:unique(p.getRecipientUserIds()))db.update("INSERT INTO notice_category_recipient(category_id,user_id) VALUES(?,?)",id,user);
 }
 public Audience resolve(NoticeCategory c,NoticeParam p){
  String type="choose".equals(c.getRecipientPolicy())?p.getAudienceType():c.getRecipientPolicy();
  if(!Set.of("all","users").contains(Objects.toString(type,"")))throw new BizException("请选择接收范围");
  List<Long> ids="users".equals(type)?("users".equals(c.getRecipientPolicy())?categoryRecipients(c.getId()):unique(p.getRecipientUserIds())):List.of();
  if("users".equals(type))validateUsers(ids);
  return new Audience(type,ids);
 }
 public void validateUsers(List<Long> ids){
  if(ids==null||ids.isEmpty()||ids.size()>1000||ids.stream().anyMatch(x->x==null||x<1))throw new BizException("请选择1至1000个有效接收用户");
  for(Long id:unique(ids))if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND (deleted=FALSE OR deleted IS NULL)",Long.class,id)!=1)throw new BizException("接收用户不存在或已删除："+id);
 }
 private static List<Long> unique(List<Long> ids){return ids==null?List.of():new ArrayList<>(new LinkedHashSet<>(ids));}
 public void saveDraft(Long id,Audience audience,boolean pushEnabled){
  db.update("UPDATE notice SET audience_type=?,push_enabled=? WHERE id=?",audience.type(),pushEnabled,id);
  db.update("DELETE FROM notice_draft_recipient WHERE notice_id=?",id);
  for(Long user:audience.userIds())db.update("INSERT INTO notice_draft_recipient(notice_id,user_id) VALUES(?,?)",id,user);
 }
 public List<Long> recipients(Long noticeId,Long publicationId){
  return publicationId==null?db.queryForList("SELECT user_id FROM notice_draft_recipient WHERE notice_id=? ORDER BY user_id",Long.class,noticeId):db.queryForList("SELECT user_id FROM notice_publication_recipient WHERE publication_id=? ORDER BY user_id",Long.class,publicationId);
 }
 @Transactional public void publish(Long id,NoticeCategory category,NoticeParam param){
  if(Boolean.TRUE.equals(param.getPushEnabled()))push.requireEnabled();
  Audience audience=resolve(category,param);
  saveDraft(id,audience,Boolean.TRUE.equals(param.getPushEnabled()));
  var values=new HashMap<String,Object>();values.put("notice_id",id);values.put("category_id",category.getId());values.put("audience_type",audience.type());values.put("unread_enabled",true);
  long publication=new SimpleJdbcInsert(db).withTableName("notice_publication").usingColumns("notice_id","category_id","audience_type","unread_enabled").usingGeneratedKeyColumns("id").executeAndReturnKey(values).longValue();
  for(Long user:audience.userIds())db.update("INSERT INTO notice_publication_recipient(publication_id,user_id) VALUES(?,?)",publication,user);
  db.update("UPDATE notice SET publication_id=?,status=1,publish_time=CURRENT_TIMESTAMP WHERE id=?",publication,id);
  if(Boolean.TRUE.equals(param.getPushEnabled()))push.enqueue(publication);
 }
 public void lock(Long id){if(db.queryForList("SELECT id FROM notice WHERE id=? FOR UPDATE",Long.class,id).isEmpty())throw new BizException("通知不存在");}
 public void cancel(Long noticeId){db.update("UPDATE notice_push_task SET status='cancelled',lease_until=NULL WHERE status IN ('pending','sending') AND publication_id IN (SELECT id FROM notice_publication WHERE notice_id=?)",noticeId);}
 public void deleteCategory(Long id){db.update("DELETE FROM notice_category_recipient WHERE category_id=?",id);}
 public void delete(Long id){
  cancel(id);db.update("DELETE FROM notice_draft_recipient WHERE notice_id=?",id);
  db.update("DELETE FROM notice_read WHERE publication_id IN (SELECT id FROM notice_publication WHERE notice_id=?)",id);
  db.update("DELETE FROM notice_publication_recipient WHERE publication_id IN (SELECT id FROM notice_publication WHERE notice_id=?)",id);
  // Keep task audit rows, which cannot be dispatched after the notice is removed.
 }
 private String visible(Long user){
  return "n.status=1 AND c.enabled=TRUE AND (p.audience_type='all'"+(user==null?"":" OR EXISTS (SELECT 1 FROM notice_publication_recipient pr WHERE pr.publication_id=p.id AND pr.user_id="+user+")")+")";
 }
 private static final String FROM=" FROM notice n JOIN notice_category c ON c.id=n.category_id JOIN notice_publication p ON p.id=n.publication_id ";
 public boolean canView(Long id,Long user){return db.queryForObject("SELECT COUNT(*)"+FROM+" WHERE n.id=? AND "+visible(user),Long.class,id)>0;}
 private String unread(Long user){
  if(user==null)return "FALSE";
  return "p.unread_enabled=TRUE AND p.published_at>(SELECT notice_read_since FROM app_user WHERE id="+user+") AND NOT EXISTS (SELECT 1 FROM notice_read r WHERE r.publication_id=p.id AND r.user_id="+user+")";
 }
 @Transactional(readOnly=true)
 public NoticePage list(NoticeQueryParam q,Long user){
  q.validate();String where=" WHERE "+visible(user)+(q.getCategoryId()==null?"":" AND n.category_id="+q.getCategoryId());
  List<Long> snapshot=q.getCategoryId()==null||q.getPageNum()!=1?List.of():db.queryForList("SELECT p.id"+FROM+where,Long.class);
  long total=db.queryForObject("SELECT COUNT(*)"+FROM+where,Long.class);
  var result=new NoticePage(q.getPageNum(),q.getPageSize(),total,snapshot);
  result.setRecords(db.query("SELECT n.id,n.category_id,c.name,n.title,n.status,n.publish_time,n.create_time,p.id AS pub_id,("+unread(user)+") AS unread"+FROM+where+" ORDER BY p.published_at DESC,p.id DESC LIMIT ? OFFSET ?",(rs,i)->{
   var v=new NoticeListVO();v.setId(rs.getLong("id"));v.setCategoryId(rs.getLong("category_id"));v.setCategoryName(rs.getString("name"));v.setTitle(rs.getString("title"));v.setStatus(rs.getInt("status"));v.setPublishTime(rs.getString("publish_time"));v.setCreateTime(rs.getString("create_time"));v.setPublicationId(rs.getLong("pub_id"));v.setUnread(rs.getBoolean("unread"));return v;
  },q.getPageSize(),q.offset()));return result;
 }
 @Transactional(readOnly=true)
 public Summary summary(Long user){
  var categories=db.query("SELECT id,name,icon_url,recipient_policy FROM notice_category WHERE enabled=TRUE ORDER BY sort_order,id",(rs,i)->new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)});
  List<CategorySummary> result=new ArrayList<>();long total=0;
  for(var c:categories){
   String where=" WHERE n.category_id=? AND "+visible(user);
   var latest=db.query("SELECT n.title,n.publish_time"+FROM+where+" ORDER BY p.published_at DESC,p.id DESC LIMIT 1",(rs,i)->new String[]{rs.getString(1),rs.getString(2)},c[0]);
   boolean allowed="all".equals(c[3])||!latest.isEmpty()||(user!=null&&"users".equals(c[3])&&categoryRecipients((Long)c[0]).contains(user));
   if(!allowed)continue;
   long count=user==null?0:db.queryForObject("SELECT COUNT(*)"+FROM+where+" AND "+unread(user),Long.class,c[0]);total+=count;
   result.add(new CategorySummary((Long)c[0],(String)c[1],(String)c[2],count,latest.isEmpty()?"":latest.getFirst()[0],latest.isEmpty()?"":latest.getFirst()[1]));
  }return new Summary(total,result);
 }
 @Transactional
 public void readCategory(Long user,Long categoryId,List<Long> publications){
  if(categoryId==null||publications==null||publications.size()>100000)throw new BizException("已读请求不合法");
  var ids=unique(publications).stream().filter(Objects::nonNull).toList();
  for(int start=0;start<ids.size();start+=500){
   var batch=ids.subList(start,Math.min(ids.size(),start+500));var args=new ArrayList<Object>();args.add(user);args.add(categoryId);args.addAll(batch);
   db.update("INSERT IGNORE INTO notice_read(publication_id,user_id) SELECT p.id,?"+FROM+" WHERE n.category_id=? AND p.id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+") AND "+visible(user),args.toArray());
  }
 }
 @Transactional
 public void readOne(Long user,Long id,Long publication){
  if(!canView(id,user))throw new BizException("通知不存在或已下架");
  db.update("INSERT IGNORE INTO notice_read(publication_id,user_id) SELECT p.id,?"+FROM+" WHERE n.id=? AND p.id=? AND "+visible(user),user,id,publication);
 }
}
