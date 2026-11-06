package com.yyx.ciyuantown;

import com.yyx.ciyuantown.business.chat.*;
import com.yyx.ciyuantown.business.post.controller.AppPostShareController;
import com.yyx.ciyuantown.business.post.service.*;
import com.yyx.ciyuantown.business.post.vo.*;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig({ChatTests.Config.class,PostShareTests.Config.class})
class PostShareTests {
 @Configuration static class Config {
  @Bean PostService posts(){return mock(PostService.class);}
  @Bean ChatPostCards cards(PostService posts){return new ChatPostCards(posts);}
  @Bean PostShareSettings shareSettings(JdbcTemplate db){return new PostShareSettings(db);}
 }
 @Autowired JdbcTemplate db;@Autowired ChatService chat;@Autowired PostService posts;@Autowired PostShareSettings settings;
 PostDetailVO post;MockMvc mvc;
 @BeforeEach void setup()throws Exception{
  var fixture=new ChatTests();fixture.db=db;fixture.setup();reset(posts);
  post=new PostDetailVO();post.setId("123456789");post.setStatus(1);post.setMode("image");post.setTitle("到底什么是 中上长相❓");post.setContent("😀".repeat(31));post.setAuthorId("3");post.setAuthorNickname("帖子作者");post.setAuthorAvatar("/uploads/avatar.png");
  post.setMedia(List.of(new PostListVO.MediaItem(10L,10L,"image","wide",1.0,100,100,0L,"","/uploads/full.jpg",null,"/uploads/preview.jpg",null,null,null,"v1","ready",null)));
  when(posts.detail(123456789L,true)).thenReturn(post);
  mvc=MockMvcBuilders.standaloneSetup(new AppPostShareController(posts,settings)).build();
 }
 ChatService.Message share(String client){return chat.send(1,new ChatService.Send("2",client,"post","伪造的客户端标题","9999",null,"123456789"));}
 @Test void cardsComeFromPublishedPostAndSurviveHistoryAndRetry(){
  var sent=share("share_client_123");assertEquals("post",sent.kind());assertEquals("",sent.content());assertNull(sent.asset());assertEquals(post.getTitle(),sent.post().title());assertEquals("/uploads/preview.jpg",sent.post().coverUrl());assertFalse(sent.post().video());assertEquals("帖子作者",sent.post().authorName());
  post.setTitle("帖子已被编辑");assertEquals(sent.post(),chat.message(2,Long.parseLong(sent.id())).post());assertEquals(sent.post(),chat.history(2,Long.parseLong(sent.conversationId()),0,50).records().getFirst().post());
  assertEquals(sent.id(),share("share_client_123").id());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_message",Integer.class));assertEquals("[帖子]"+sent.post().title(),chat.conversations(2).getFirst().lastMsg());assertEquals(1,chat.conversations(2).getFirst().unread());
 }
 @Test void blockedRestrictedAndUnavailablePostsCannotBeShared(){
  post.setStatus(0);assertThrows(BizException.class,()->share("not_public_123"));post.setStatus(1);
  db.update("INSERT INTO user_block(owner_id,target_id) VALUES(2,1)");assertThrows(BizException.class,()->share("blocked_share_1"));db.update("DELETE FROM user_block");
  db.update("INSERT INTO chat_restriction(user_id,conversation_id,blocked,muted_until,operator_id,updated_at) VALUES(1,0,TRUE,0,1,0)");assertThrows(BizException.class,()->share("muted_share_123"));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_message",Integer.class));
 }
 @Test void authorFramesSurviveSendHistoryAndLegacyCardsStillDecode(){
  post.setAuthorAvatarFrame("shop-1");post.setAuthorAvatarFrameExpiresAt(System.currentTimeMillis()+60000);
  var sent=share("frame_share_123");assertEquals("shop-1",sent.post().authorAvatarFrame());assertEquals(post.getAuthorAvatarFrameExpiresAt(),sent.post().authorAvatarFrameExpiresAt());
  assertEquals(sent.post(),chat.history(2,Long.parseLong(sent.conversationId()),0,50).records().getFirst().post());
  var legacy=ChatPostCards.decode("{\"id\":\"1\",\"title\":\"旧分享\",\"coverUrl\":\"\",\"authorId\":\"3\",\"authorName\":\"作者\",\"authorAvatar\":\"\",\"video\":false}");
  assertNotNull(legacy);assertEquals("旧分享",legacy.title());assertNull(legacy.authorAvatarFrame());assertNull(legacy.authorAvatarFrameExpiresAt());
 }
 @Test void legacyMessagesResolveAuthorFramesWithoutChangingStoredSnapshots()throws Exception{
  var sent=share("legacy_frame_123");long message=Long.parseLong(sent.id()),conversation=Long.parseLong(sent.conversationId()),expiry=System.currentTimeMillis()+60000;
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var legacy=json.valueToTree(sent.post());((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove(List.of("authorAvatarFrame","authorAvatarFrameExpiresAt"));String stored=json.writeValueAsString(legacy);
  db.update("UPDATE chat_message SET content=? WHERE id=?",stored,message);
  db.update("UPDATE app_user SET avatar_frame='sakura-mist',avatar_frame_expires_at=? WHERE id=3",expiry);
  var single=chat.message(2,message).post();assertEquals("sakura-mist",single.authorAvatarFrame());assertEquals(Long.valueOf(expiry),single.authorAvatarFrameExpiresAt());assertEquals(sent.post().title(),single.title());
  assertEquals(single,chat.history(2,conversation,0,50).records().getFirst().post());assertEquals(single,share("legacy_frame_123").post());
  assertEquals(stored,db.queryForObject("SELECT content FROM chat_message WHERE id=?",String.class,message));
  db.update("UPDATE app_user SET avatar_frame='',avatar_frame_expires_at=NULL WHERE id=3");assertEquals("",chat.message(2,message).post().authorAvatarFrame());assertNull(chat.message(2,message).post().authorAvatarFrameExpiresAt());
  chat.revoke(1,message);assertNull(chat.message(2,message).post());
 }
 @Test void postCardsSupportRepliesAndRevocationWithoutLeakingSnapshot(){
  var source=share("source_share_12");var reply=chat.send(2,new ChatService.Send("1","quote_share_123","text","看看这个",null,source.id()));assertEquals("[帖子]"+post.getTitle(),reply.reply().summary());
  assertThrows(BizException.class,()->chat.message(3,Long.parseLong(source.id())));chat.revoke(1,Long.parseLong(source.id()));
  var revoked=chat.message(2,Long.parseLong(source.id()));assertNull(revoked.post());assertEquals("",revoked.content());assertEquals("revoked",chat.message(2,Long.parseLong(reply.id())).reply().status());assertEquals("",chat.message(2,Long.parseLong(reply.id())).reply().summary());
 }
 @Test void videoAndTextOnlyCardsHaveUsableFallbacks(){
  post.setMode("video");post.setMedia(List.of(new PostListVO.MediaItem(10L,10L,"video","wide",1.0,100,100,100L,"","/uploads/video.mp4","/uploads/cover.jpg",null,null,null,null,"v1","ready",null)));
  var video=share("video_share_12");assertTrue(video.post().video());assertEquals("/uploads/cover.jpg",video.post().coverUrl());
  post.setMedia(List.of());post.setTitle("");post.setContent("纯文字内容");var text=share("text_share_123");assertEquals("",text.post().coverUrl());assertEquals("纯文字内容",text.post().title());
 }
 @Test void domainDefaultsPersistsAndRejectsInvalidValues(){
  assertEquals("",settings.get().baseUrl());assertEquals("https://share.example.com",settings.save(new PostShareSettings.Config(" https://share.example.com/ ")).baseUrl());assertEquals("https://share.example.com",settings.get().baseUrl());
  for(String invalid:List.of("javascript:alert(1)","https://user:pass@example.com","https://example.com/path","https://example.com?x=1","https://example.com#frag","not-a-domain"))assertThrows(BizException.class,()->settings.save(new PostShareSettings.Config(invalid)));
  assertEquals("",settings.save(new PostShareSettings.Config("")).baseUrl());
 }
 @Test void copyContainsTitleUnicodeExcerptShortPathAndConfiguredDomain()throws Exception{
  mvc.perform(get("/app/share/posts/123456789")).andExpect(status().isOk()).andExpect(jsonPath("$.data.url").value("http://localhost/app/share/p/21i3v9")).andExpect(jsonPath("$.data.text").value(post.getTitle()+" "+"😀".repeat(28)+"... http://localhost/app/share/p/21i3v9 打开【次元町】，这篇帖子超精彩！"));
  settings.save(new PostShareSettings.Config("https://share.example.com"));mvc.perform(get("/app/share/posts/123456789")).andExpect(jsonPath("$.data.url").value("https://share.example.com/app/share/p/21i3v9"));
 }
 @Test void browserPageEscapesContentAndChecksCurrentAvailability()throws Exception{
  post.setTitle("<script>alert(1)</script>");post.setContent("<img src=x onerror=alert(1)>");
  String body=mvc.perform(get("/app/share/p/21i3v9")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  assertTrue(body.contains("&lt;script&gt;"));assertFalse(body.contains("<script>"));assertTrue(body.contains("&lt;img src=x"));
  post.setStatus(2);mvc.perform(get("/app/share/p/21i3v9")).andExpect(status().isNotFound());mvc.perform(get("/app/share/p/not_valid!")).andExpect(status().isNotFound());
 }
}
