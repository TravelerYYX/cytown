package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.interaction.service.*;
import com.yyx.ciyuantown.business.post.service.*;
import com.yyx.ciyuantown.business.post.dto.*;
import com.yyx.ciyuantown.business.post.mapper.PostMapper;
import com.yyx.ciyuantown.business.publish.service.PublishService;
import com.yyx.ciyuantown.business.publish.dto.PublishRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB",matches="true")
@SpringBootTest(properties={"ciyuantown.redis.enabled=false"}) @AutoConfigureMockMvc @Transactional
class SocialInboxIntegrationTests {
 @Autowired JdbcTemplate db;
 @Autowired SocialInboxService inbox;
 @Autowired InteractionService interactions;
 @Autowired CommentService comments;
 @Autowired PostService posts;
 @Autowired PostMapper postMapper;
 @Autowired PublishService publishing;
 @Autowired ObjectMapper json;
 @Autowired org.mybatis.spring.SqlSessionTemplate session;
 @Autowired MockMvc mvc;
 @Autowired JwtUtil jwt;
 long owner,actor,other,post;
 long insert(String table,Map<String,Object> data){return new SimpleJdbcInsert(db).withTableName(table).usingGeneratedKeyColumns("id").usingColumns(data.keySet().toArray(String[]::new)).executeAndReturnKey(data).longValue();}
 long user(String name){return insert("app_user",Map.of("email",UUID.randomUUID()+"@test.local","nickname",name));}
 long comment(String text,Long parent){var values=new HashMap<String,Object>();values.put("post_id",post);values.put("author_id",actor);values.put("author_nickname","通知用户");values.put("content",text);values.put("parent_id",parent);return insert("comment",values);}
 @BeforeEach void setup(){assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_"));owner=user("作者");actor=user("互动者");other=user("回复对象");post=insert("post",Map.of("author_id",owner,"author_nickname","作者","title","帖子标题","content","帖子正文","status",1));}
 @Test void deliveriesAreTransactionalDeduplicatedAndReadIsRecipientScoped(){
  inbox.deliver(owner,actor,"post_like",post,null,"fixture-like");inbox.deliver(owner,actor,"post_like",post,null,"fixture-like");
  inbox.deliver(owner,owner,"post_like",post,null,"self");inbox.deliver(other,actor,"follow",null,null,"foreign");
  assertEquals(1L,inbox.summary(owner).get("like"));
  String id=db.queryForObject("SELECT CAST(id AS CHAR) FROM social_notification WHERE business_key='fixture-like'",String.class);
  String foreign=db.queryForObject("SELECT CAST(id AS CHAR) FROM social_notification WHERE business_key='foreign'",String.class);
  inbox.read(owner,List.of(id,foreign));assertEquals(0L,inbox.summary(owner).get("unreadCount"));assertEquals(1L,inbox.summary(other).get("unreadCount"));
  inbox.deliver(owner,actor,"mention",post,null,"new-during-read");inbox.read(owner,List.of(id));assertEquals(1L,inbox.summary(owner).get("mention"));
 }
 @Test void notificationReferencesUseTitleThenContentThenTimestamp(){
  inbox.deliver(owner,actor,"post_like",post,null,"label");
  assertEquals("帖子标题",firstNotification().get("postLabel"));
  db.update("UPDATE post SET title='  ' WHERE id=?",post);assertEquals("帖子正文",firstNotification().get("postLabel"));
  db.update("UPDATE post SET content='' WHERE id=?",post);assertTrue(firstNotification().get("postLabel").toString().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
  assertEquals(String.valueOf(actor),firstNotification().get("actorId"));
 }
 @SuppressWarnings("unchecked") Map<String,Object> firstNotification(){return ((List<Map<String,Object>>)inbox.list(owner,"like",false,1,20).get("records")).getFirst();}
 @Test void commentReplyRecipientsAndBothLikeTypes(){
  long root=insert("comment",Map.of("post_id",post,"author_id",other,"author_nickname","回复对象","content","根评论"));
  var reply=comments.create(post,actor,new CommentCreateParam(root,root,"回复",null,null,""));
  assertEquals(1L,inbox.summary(owner).get("reply"));assertEquals(1L,inbox.summary(other).get("reply"));
  comments.react(post,Long.valueOf(reply.getId()),other,"like");interactions.toggleLike(actor,post);
  assertEquals(1L,inbox.summary(actor).get("like"));assertEquals(1L,inbox.summary(owner).get("like"));
  interactions.toggleLike(actor,post);interactions.toggleLike(actor,post);
  assertEquals(1L,inbox.summary(owner).get("like"));
  interactions.toggleFollow(owner,actor);
  assertEquals(1L,inbox.list(owner,"reply",true,1,20).get("total"));
  interactions.toggleFollow(owner,actor);
  assertEquals(0L,inbox.list(owner,"reply",true,1,20).get("total"));
 }
 @Test void publicationAndRepostApprovalOnlyDeliverOnce() throws Exception {
  long channel=insert("home_channel",Map.of("name","转发测试","enabled",true,"allow_publish",true,"admin_only",false));
  db.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?)",actor,other);
  db.update("INSERT INTO system_config(config_key,config_value,remark) VALUES('publish_review_required','1','test') ON DUPLICATE KEY UPDATE config_value='1'");
  var blocks=json.readTree("[{\"type\":\"paragraph\",\"runs\":[{\"type\":\"mention\",\"userId\":\""+other+"\"}]}]");
  var result=publishing.submit(actor,new PublishRequest.Submit(null,"post",channel,"","",blocks,List.of(),List.of(),List.of(),true,false,false,null,post));
  long repost=Long.parseLong(result.id());assertEquals(0,result.status());assertEquals(0L,inbox.summary(other).get("repost"));
  posts.updateStatus(repost,1);posts.updateStatus(repost,2);posts.updateStatus(repost,1);
  assertEquals(1L,inbox.summary(other).get("repost"));assertEquals(1,postMapper.selectById(post).getShareCount());
  assertEquals(1,interactions.withState(List.of(posts.detail(post,false)),null).getFirst().getShareCount());
  assertEquals(String.valueOf(post),posts.detail(repost,false).getRepostOfPostId());assertTrue(posts.detail(repost,false).getRepost().available());
  posts.updateStatus(post,2);assertFalse(posts.detail(repost,false).getRepost().available());
 }
 @Test void locatesOffscreenRootsAndLateNestedRepliesWithoutLoadingAllPages(){
  long root=comment("目标根评论",null);
  db.update("UPDATE `comment` SET create_time='2020-01-01' WHERE id=?",root);
  for(int i=0;i<25;i++)comment("其他根评论"+i,null);
  long target=0;for(int i=0;i<45;i++)target=comment("回复"+i,root);
  var q=new CommentQueryParam();q.setSort("latest");
  var focus=comments.locate(post,target,q,null);
  assertEquals(2,focus.rootPage());assertEquals(3,focus.replyPage());assertEquals(String.valueOf(root),focus.rootId());
  long expected=target;assertTrue(focus.replies().getRecords().stream().anyMatch(row->row.getId().equals(String.valueOf(expected))));
  assertTrue(focus.roots().getRecords().stream().anyMatch(row->row.getId().equals(String.valueOf(root))));
  db.update("UPDATE `comment` SET pinned=1,like_count=99 WHERE id=?",root);session.clearCache();q.setSort("hot");assertEquals(1,comments.locate(post,target,q,null).rootPage());
  long invalid=insert("post",Map.of("author_nickname","另帖","title","另帖","status",1));
  assertThrows(RuntimeException.class,()->comments.locate(invalid,expected,q,null));
  db.update("UPDATE `comment` SET deleted=1 WHERE id=?",target);session.clearCache();assertThrows(RuntimeException.class,()->comments.locate(post,expected,q,null));
 }
 @Test void previewCanReadUnpublishedPostsButPublicCommentsCannot(){
  comment("审核中评论",null);db.update("UPDATE post SET status=0 WHERE id=?",post);
  assertEquals(1,comments.listPreview(post,new CommentQueryParam()).getTotal());
  assertThrows(RuntimeException.class,()->comments.list(post,new CommentQueryParam(),null));
  db.update("UPDATE post SET like_count=7 WHERE id=?",post);session.clearCache();assertEquals(7,posts.detail(post,false).getLikeCount());
  db.update("UPDATE post SET like_count=9 WHERE id=?",post);session.clearCache();assertEquals(9,posts.detail(post,false).getLikeCount());
 }
 @Test void inboxRequiresLoginAndUnpublishedPreviewRequiresAdministrator() throws Exception {
  var get=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/app/social/summary");
  mvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("8888"));
  String token="Bearer "+jwt.createAccessToken(owner,"app",0);
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/app/social/summary").header("Authorization",token))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("0000"));
  comment("待审核",null);db.update("UPDATE post SET status=0 WHERE id=?",post);session.clearCache();
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/post/"+post+"/comments").header("Authorization",token))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(org.hamcrest.Matchers.not("0000")));
  long admin=insert("admin_user",Map.of("username","social-admin-"+UUID.randomUUID().toString().substring(0,8),"password","fixture","nickname","预览管理员","user_type",0));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/post/"+post+"/comments").header("Authorization","Bearer "+jwt.createAccessToken(admin,"admin",0,0)))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("0000"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.total").value(1));
 }
}
