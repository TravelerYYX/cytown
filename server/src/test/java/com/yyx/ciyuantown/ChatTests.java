package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.chat.*;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(ChatTests.Config.class)
class ChatTests {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:chat;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");}
  @Bean JdbcTemplate db(DataSource source){return new JdbcTemplate(source);}
  @Bean PlatformTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
  @Bean ObjectMapper json(){return new ObjectMapper();}
  @Bean ChatSettings settings(JdbcTemplate db,ObjectMapper json){return new ChatSettings(db,json);}
  @Bean ChatRealtime realtime(JdbcTemplate db,ObjectMapper json){return new ChatRealtime(db,json);}
  @Bean ChatPolicy policy(JdbcTemplate db){return new ChatPolicy(db);}
  @Bean ChatService chat(JdbcTemplate db,ChatSettings settings,ChatRealtime realtime,ChatPolicy policy){return new ChatService(db,settings,realtime,policy);}
 }
 @Autowired JdbcTemplate db;@Autowired ChatService chat;@Autowired ChatSettings settings;
 @BeforeEach void setup()throws Exception{
  db.execute("DROP ALL OBJECTS");
  db.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY,nickname VARCHAR(40),avatar VARCHAR(200) DEFAULT '',avatar_frame VARCHAR(64) DEFAULT '',avatar_frame_expires_at BIGINT,status INT DEFAULT 0,deleted BOOLEAN DEFAULT FALSE,token_version INT DEFAULT 0)");
  db.execute("INSERT INTO app_user(id,nickname) VALUES(1,'A'),(2,'B'),(3,'C')");
  db.execute("CREATE TABLE user_block(owner_id BIGINT,target_id BIGINT,PRIMARY KEY(owner_id,target_id))");
  db.execute("CREATE TABLE user_follow(id BIGINT AUTO_INCREMENT PRIMARY KEY,follower_id BIGINT,followee_id BIGINT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(follower_id,followee_id))");
  db.execute("CREATE TABLE system_config(config_key VARCHAR(100) PRIMARY KEY,config_value LONGTEXT,remark VARCHAR(100))");
  db.execute("CREATE TABLE notice_push_device(cid VARCHAR(128) PRIMARY KEY,user_id BIGINT,token_version INT)");
  for(String sql:Files.readString(Path.of("doc/sql/p27-chat.sql")).replaceAll("(?m)^--.*$","").split(";"))if(!sql.isBlank())db.execute(sql);
  db.execute("CREATE TABLE media_cleanup_task(id VARCHAR(64) PRIMARY KEY,completed BOOLEAN DEFAULT FALSE,error_message VARCHAR(255) DEFAULT '')");
  for(String sql:Files.readString(Path.of("doc/sql/p28-chat-admin.sql")).replace("CHARACTER SET ascii COLLATE ascii_bin", "").replaceAll("(?m)^--.*$","").split(";"))if(!sql.isBlank())db.execute(sql);
  for(String sql:Files.readString(Path.of("doc/sql/p29-chat-reply.sql")).replaceAll("(?m)^--.*$","").split(";"))if(!sql.isBlank())db.execute(sql);
 }
 void follow(long a,long b){db.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?)",a,b);chat.relationship(a,b);}
 ChatService.Message text(long a,long b,String client){return chat.send(a,new ChatService.Send(""+b,client,"text","hello 👋",null));}
 long asset(String type){var values=Map.<String,Object>of("owner_id",1,"kind",type,"state","ready","version","v1","mime","image/png","extension","png","size_bytes",12,"manifest_json","{}","config_json","{}","created_at",System.currentTimeMillis());return new org.springframework.jdbc.core.simple.SimpleJdbcInsert(db).withTableName("chat_asset").usingColumns(values.keySet().toArray(String[]::new)).usingGeneratedKeyColumns("id").executeAndReturnKey(values).longValue();}
 ChatService.Message media(String kind){follow(1,2);long asset=asset(kind);return chat.send(1,new ChatService.Send("2","media_123456",kind,"",""+asset));}
 @Test void asymmetricFollowMutualFollowAndReply(){
  follow(1,2);assertEquals(1,chat.conversations(1).size());assertTrue(chat.conversations(2).isEmpty());
  var empty=chat.conversations(1).getFirst();assertEquals("暂无消息",empty.lastMsg());assertEquals(0,empty.lastTime());assertEquals("B",empty.name());
  var first=text(1,2,"client_123456");assertEquals(1,chat.conversations(2).size());assertEquals(1,chat.conversations(2).getFirst().unread());assertEquals("hello 👋",chat.conversations(2).getFirst().lastMsg());
  text(2,1,"reply_1234567");db.update("DELETE FROM user_follow");assertEquals(1,chat.conversations(1).size());assertEquals(1,chat.conversations(2).size());text(1,2,"after_1234567");
  follow(1,3);follow(3,1);assertEquals(1,chat.conversations(3).size());assertEquals("1",chat.conversations(3).getFirst().peerId());
 }
 @Test void conversationsExposeEachPeersCurrentAvatarFrame(){
  follow(1,2);follow(2,1);long expiry=System.currentTimeMillis()+60000;
  db.update("UPDATE app_user SET avatar_frame='star-sea' WHERE id=1");
  db.update("UPDATE app_user SET avatar_frame='sakura-mist',avatar_frame_expires_at=? WHERE id=2",expiry);
  var peer=chat.conversations(1).getFirst();assertEquals("sakura-mist",peer.avatarFrame());assertEquals(Long.valueOf(expiry),peer.avatarFrameExpiresAt());
  var self=chat.conversations(2).getFirst();assertEquals("star-sea",self.avatarFrame());assertNull(self.avatarFrameExpiresAt());
  db.update("UPDATE app_user SET avatar_frame='',avatar_frame_expires_at=NULL WHERE id=2");
  assertEquals("",chat.conversations(1).getFirst().avatarFrame());assertNull(chat.conversations(1).getFirst().avatarFrameExpiresAt());
 }
 @Test void emptyConversationsStayHiddenAndStrangersCanStartChat(){
  follow(1,2);db.update("DELETE FROM user_follow");assertTrue(chat.conversations(1).isEmpty());
  var opened=chat.open(1,2);assertEquals(opened.id(),chat.open(1,2).id());
  assertTrue(chat.conversations(1).isEmpty());assertTrue(chat.conversations(2).isEmpty());
  assertEquals("2",chat.conversation(1,Long.parseLong(opened.id())).peerId());
  assertThrows(BizException.class,()->chat.conversation(3,Long.parseLong(opened.id())));
  assertThrows(BizException.class,()->chat.open(1,1));
  text(1,2,"unfollow_12345");assertEquals(1,chat.conversations(1).size());assertEquals(1,chat.conversations(2).size());
  text(3,2,"stranger_12345");assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_follow",Integer.class));
 }
 @Test void directChatStillRespectsSendRestrictions(){
  db.update("INSERT INTO chat_restriction(user_id,conversation_id,blocked,muted_until,operator_id,updated_at) VALUES(1,0,TRUE,0,1,0)");
  assertThrows(BizException.class,()->chat.open(1,2));assertThrows(BizException.class,()->text(1,2,"blocked_direct"));
 }
 @Test void idempotencyPaginationAndReadAreStable(){follow(1,2);var first=text(1,2,"client_123456");assertEquals(first.id(),text(1,2,"client_123456").id());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_message",Integer.class));for(int i=0;i<5;i++)text(1,2,"next_client_"+i);long c=Long.parseLong(first.conversationId());var page=chat.history(2,c,0,3);assertEquals(3,page.records().size());assertFalse(page.nextCursor().isEmpty());var next=chat.history(2,c,Long.parseLong(page.nextCursor()),3);assertEquals(3,next.records().size());assertTrue(next.nextCursor().isEmpty());long last=Long.parseLong(page.records().getFirst().id());chat.read(2,c,last);long sequence=chat.sync(2,0).cursor();chat.read(2,c,last);assertEquals(sequence,chat.sync(2,0).cursor());assertEquals(0,chat.conversations(2).getFirst().unread());assertTrue(chat.message(1,Long.parseLong(first.id())).canRevoke());}
 @Test void textRevocationIgnoresReadAndDeadlineIsServerEnforced(){follow(1,2);var m=text(1,2,"text_client_123");long id=Long.parseLong(m.id());chat.read(2,Long.parseLong(m.conversationId()),id);assertTrue(chat.revoke(1,id).revoked());assertTrue(chat.message(2,id).content().isEmpty());assertFalse(chat.message(1,id).canRevoke());var expired=text(1,2,"expired_123456");db.update("UPDATE chat_message SET revoke_until=0 WHERE id=?",Long.parseLong(expired.id()));assertThrows(BizException.class,()->chat.revoke(1,Long.parseLong(expired.id())));}
 @Test void imageAutocacheDoesNotProtectButSavingDoesAndFailureReleases(){var m=media("image");long id=Long.parseLong(m.id());assertTrue(chat.message(1,id).canRevoke());chat.operation(2,id,"save_operation_1","saved","reserve");assertFalse(chat.message(1,id).canRevoke());assertThrows(BizException.class,()->chat.revoke(1,id));chat.operation(2,id,"save_operation_1","saved","fail");assertTrue(chat.message(1,id).canRevoke());chat.operation(2,id,"save_operation_2","saved","reserve");chat.operation(2,id,"save_operation_2","saved","confirm");assertFalse(chat.message(1,id).canRevoke());assertTrue(chat.message(1,id).protectedReceipt());assertEquals("confirmed",chat.operation(2,id,"save_operation_2","saved","reserve").state());}
 @Test void videoNeedsCompleteReceiptAndWrongUserCannotAcknowledge(){var m=media("video");long id=Long.parseLong(m.id());assertTrue(chat.message(1,id).canRevoke());assertThrows(BizException.class,()->chat.operation(1,id,"video_operation","received","reserve"));assertThrows(BizException.class,()->chat.operation(2,id,"video_operation","saved","reserve"));chat.operation(2,id,"video_operation","received","reserve");chat.operation(2,id,"video_operation","received","confirm");assertThrows(BizException.class,()->chat.revoke(1,id));assertThrows(BizException.class,()->chat.message(3,id));}
 @Test void reserveAndRevokeHaveOneWinner()throws Exception{var m=media("image");long id=Long.parseLong(m.id());var gate=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{gate.await();try{chat.revoke(1,id);return true;}catch(BizException e){return false;}});var b=pool.submit(()->{gate.await();try{chat.operation(2,id,"race_operation_1","saved","reserve");return true;}catch(BizException e){return false;}});gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));}}
 @Test void settingsAreIndependentAndDeadlineIsSnapshotted(){follow(1,2);var before=text(1,2,"before_config_1");var defaults=settings.get();settings.save(new ChatSettings.Config(5,defaults.images(),defaults.videos()));var after=text(1,2,"after_config_123");assertEquals(120000,before.revokeUntil()-before.sentAt());assertEquals(5000,after.revokeUntil()-after.sentAt());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM system_config WHERE config_key='media_processing'",Integer.class));assertThrows(BizException.class,()->settings.save(new ChatSettings.Config(-1,defaults.images(),defaults.videos())));}
 @Test void pushOutboxAndRevocationAreTransactional(){settings.saveOfflineReminder(new ChatSettings.OfflineReminder(true));db.update("INSERT INTO notice_push_device VALUES('device_B',2,0)");follow(1,2);var m=text(1,2,"push_client_123");assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_push_task WHERE state='pending'",Integer.class));chat.revoke(1,Long.parseLong(m.id()));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_push_task WHERE state='cancelled'",Integer.class));}
}
