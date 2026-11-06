package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.notice.dto.*;
import com.yyx.ciyuantown.business.notice.entity.NoticeCategory;
import com.yyx.ciyuantown.business.notice.service.*;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringJUnitConfig(NoticeDeliveryTests.Config.class)
@TestPropertySource(properties={"ciyuantown.jwt.secret=notice-test-key-only-not-production"})
class NoticeDeliveryTests {
 @org.springframework.boot.test.context.TestConfiguration @EnableTransactionManagement static class Config {
  @Bean DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:notices;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
  @Bean JdbcTemplate db(DataSource source){return new JdbcTemplate(source);}
  @Bean PlatformTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
  @Bean ObjectMapper json(){return new ObjectMapper();}
  @Bean NoticePushService push(JdbcTemplate db,ObjectMapper json){return spy(new NoticePushService(db,json));}
  @Bean NoticeDeliveryService delivery(JdbcTemplate db,NoticePushService push){return new NoticeDeliveryService(db,push);}
 }
 @Autowired JdbcTemplate db;@Autowired NoticeDeliveryService delivery;@Autowired NoticePushService push;
 @BeforeEach void setup()throws Exception{
  reset(push);
  db.execute("DROP ALL OBJECTS");
  db.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY,deleted BOOLEAN DEFAULT FALSE,status INT DEFAULT 0,token_version INT DEFAULT 0,create_time TIMESTAMP DEFAULT TIMESTAMP '2025-01-01 00:00:00')");
  db.execute("INSERT INTO app_user(id) VALUES(1),(2),(3)");
  db.execute("CREATE TABLE notice_category(id BIGINT PRIMARY KEY,name VARCHAR(20),icon_url VARCHAR(100) DEFAULT '',sort_order INT DEFAULT 0,enabled BOOLEAN DEFAULT TRUE)");
  db.execute("CREATE TABLE notice(id BIGINT PRIMARY KEY,category_id BIGINT,title VARCHAR(100),status INT DEFAULT 0,publish_time TIMESTAMP,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
  for(String sql:Files.readString(Path.of("doc/sql/p25-notice-delivery.sql")).replaceAll("(?m)^--.*$","").split(";")){
   String trimmed=sql.trim();if(trimmed.isEmpty()||trimmed.startsWith("SET NAMES")||trimmed.startsWith("UPDATE notice n JOIN"))continue;db.execute(trimmed);
  }
  db.execute("INSERT INTO notice_category(id,name) VALUES(1,'测试'),(2,'私信')");
 }
 NoticeCategory category(String policy){var c=new NoticeCategory();c.setId(1L);c.setRecipientPolicy(policy);return c;}
 NoticeParam param(String type,Long...users){var p=new NoticeParam();p.setAudienceType(type);p.setRecipientUserIds(Arrays.asList(users));p.setPushEnabled(false);return p;}
 long publish(long id,String policy,NoticeParam param){db.update("INSERT INTO notice(id,category_id,title) VALUES(?,1,?)",id,"通知"+id);delivery.publish(id,category(policy),param);return db.queryForObject("SELECT publication_id FROM notice WHERE id=?",Long.class,id);}
 NoticeQueryParam query(){var q=new NoticeQueryParam();q.setCategoryId(1L);return q;}
 @Test void fixedAudienceOverridesSenderAndRejectsDeletedUsers(){
  var p=new NoticeCategoryParam();p.setRecipientPolicy("users");p.setRecipientUserIds(List.of(1L,1L));delivery.validatePolicy(p);delivery.saveCategoryRecipients(1L,p);
  assertEquals(new NoticeDeliveryService.Audience("users",List.of(1L)),delivery.resolve(category("users"),param("all",2L)));
  assertEquals("all",delivery.resolve(category("all"),param("users",2L)).type());
  assertEquals(List.of(2L),delivery.resolve(category("choose"),param("users",2L)).userIds());
  assertThrows(BizException.class,()->delivery.resolve(category("choose"),param("users")));
  db.update("UPDATE app_user SET deleted=TRUE WHERE id=2");assertThrows(BizException.class,()->delivery.resolve(category("choose"),param("users",2L)));
 }
 @Test void listDetailSummaryAndGuestUseIdenticalVisibility(){
  publish(10,"choose",param("users",1L));publish(11,"all",param("all"));
  assertEquals(2,delivery.list(query(),1L).getTotal());assertEquals(1,delivery.list(query(),2L).getTotal());assertEquals(1,delivery.list(query(),null).getTotal());
  assertTrue(delivery.canView(10L,1L));assertFalse(delivery.canView(10L,2L));assertFalse(delivery.canView(10L,null));
  assertEquals(2,delivery.summary(1L).unreadCount());assertEquals(1,delivery.summary(2L).unreadCount());assertEquals(0,delivery.summary(null).unreadCount());
  db.update("UPDATE notice_category SET enabled=FALSE WHERE id=1");assertEquals(0,delivery.list(query(),1L).getTotal());assertFalse(delivery.canView(11L,1L));
 }
 @Test void readsAreIdempotentAndDoNotClearPublicationsArrivingAfterList(){
  long first=publish(10,"all",param("all"));var snapshot=delivery.list(query(),1L).getReadPublicationIds();long second=publish(11,"all",param("all"));
  delivery.readCategory(1L,1L,snapshot);delivery.readCategory(1L,1L,snapshot);assertEquals(1,delivery.summary(1L).unreadCount());assertEquals(2,delivery.summary(2L).unreadCount());
  delivery.readOne(1L,11L,second);assertEquals(0,delivery.summary(1L).unreadCount());
  delivery.readCategory(2L,2L,List.of(first));assertEquals(2,delivery.summary(2L).unreadCount());
 }
 @Test void historicalAndNewlyRegisteredUsersStartReadButCanBrowse(){
  long pub=publish(10,"all",param("all"));db.update("UPDATE notice_publication SET unread_enabled=FALSE WHERE id=?",pub);assertEquals(0,delivery.summary(1L).unreadCount());
  publish(11,"all",param("all"));db.update("INSERT INTO app_user(id,create_time) VALUES(4,DATEADD('DAY',1,CURRENT_TIMESTAMP))");assertEquals(0,delivery.summary(4L).unreadCount());assertEquals(2,delivery.list(query(),4L).getTotal());
 }
 @Test void categoryChangesDoNotRewritePublishedAudienceAndRepublishCreatesNewUnread(){
  long old=publish(10,"choose",param("users",1L));delivery.readOne(1L,10L,old);
  var c=new NoticeCategoryParam();c.setRecipientPolicy("users");c.setRecipientUserIds(List.of(2L));delivery.saveCategoryRecipients(1L,c);
  assertTrue(delivery.canView(10L,1L));assertFalse(delivery.canView(10L,2L));
  db.update("UPDATE notice SET status=0 WHERE id=10");delivery.publish(10L,category("users"),param("all"));
  assertFalse(delivery.canView(10L,1L));assertTrue(delivery.canView(10L,2L));assertEquals(1,delivery.summary(2L).unreadCount());assertNotEquals(old,db.queryForObject("SELECT publication_id FROM notice WHERE id=10",Long.class));
 }
 @Test void privateReadsCannotBeForgedAndOldDetailsCannotReadNewPublication(){
  long old=publish(10,"choose",param("users",1L));assertThrows(BizException.class,()->delivery.readOne(2L,10L,old));
  delivery.readCategory(2L,1L,List.of(old));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notice_read",Integer.class));
  delivery.publish(10L,category("choose"),param("users",1L));delivery.readOne(1L,10L,old);assertEquals(1,delivery.summary(1L).unreadCount());
 }
 @Test void pushConfigEncryptsMasksAndPreservesSecretWhenBlank(){
  assertThrows(BizException.class,()->push.save(new NoticePushService.Config(true,"app","http://invalid","short",false)));
  String secret="a".repeat(40);var saved=push.save(new NoticePushService.Config(true,"app","https://example.com/push",secret,false));
  assertTrue(saved.secretConfigured());assertEquals("",saved.secret());assertFalse(db.queryForObject("SELECT secret_cipher FROM notice_push_config",String.class).contains(secret));
  push.save(new NoticePushService.Config(true,"app","https://example.com/push","",true));assertEquals(secret,push.config().secret());
 }
 @Test void onlyBoundRecipientDevicesAreQueuedAndUnbindCancelsTasks(){
  push.bind(1L,"device1");push.bind(2L,"device2");long pub=publish(10,"choose",param("users",1L));push.enqueue(pub);push.enqueue(pub);
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notice_push_task",Integer.class));push.unbind(1L,"device1");assertEquals("cancelled",db.queryForObject("SELECT status FROM notice_push_task",String.class));
 }
 @Test void switchedDevicesAndGlobalDisableCancelOldTasks(){
  push.bind(1L,"device1");long pub=publish(10,"all",param("all"));push.enqueue(pub);push.bind(2L,"device1");assertEquals("cancelled",db.queryForObject("SELECT status FROM notice_push_task",String.class));
  publish(11,"all",param("all"));push.enqueue(db.queryForObject("SELECT publication_id FROM notice WHERE id=11",Long.class));push.save(new NoticePushService.Config(false,"","","",false));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notice_push_task WHERE status='pending'",Integer.class));
 }
 @Test void disabledPushRejectsPublicationButAllowsDraft(){
  db.update("INSERT INTO notice(id,category_id,title) VALUES(10,1,'draft')");delivery.saveDraft(10L,new NoticeDeliveryService.Audience("all",List.of()),true);
  var p=param("all");p.setPushEnabled(true);assertThrows(BizException.class,()->delivery.publish(10L,category("all"),p));
 }
 @Test void downAndDeletedNoticesDisappearAndCancelPendingPush(){
  push.bind(1L,"device1");long pub=publish(10,"all",param("all"));push.enqueue(pub);delivery.cancel(10L);db.update("UPDATE notice SET status=0 WHERE id=10");assertEquals(0,delivery.summary(1L).unreadCount());assertFalse(delivery.canView(10L,1L));
  delivery.delete(10L);db.update("DELETE FROM notice WHERE id=10");assertEquals("cancelled",db.queryForObject("SELECT status FROM notice_push_task",String.class));
 }
 void queue(){push.save(new NoticePushService.Config(true,"app","https://example.com/push","x".repeat(40),false));push.bind(1L,"device1");long pub=publish(10,"all",param("all"));push.enqueue(pub);}
 @Test void workerMarksProviderAcceptanceAndDoesNotSendAgain(){
  queue();doNothing().when(push).send(any(),anyMap());push.dispatch();push.dispatch();assertEquals("accepted",db.queryForObject("SELECT status FROM notice_push_task",String.class));verify(push,times(1)).send(any(),anyMap());
  delivery.publish(10L,category("all"),param("all"));push.enqueue(db.queryForObject("SELECT publication_id FROM notice WHERE id=10",Long.class));assertEquals(1,push.tasks(10L).size());assertEquals("pending",push.tasks(10L).getFirst().get("status"));
 }
 @Test void failedPushBacksOffStopsAndCanBeRetried(){
  queue();doThrow(new BizException("simulated rejection")).when(push).send(any(),anyMap());
  for(int i=0;i<5;i++){db.update("UPDATE notice_push_task SET next_attempt_at=CURRENT_TIMESTAMP");push.dispatch();}
  assertEquals("failed",db.queryForObject("SELECT status FROM notice_push_task",String.class));verify(push,times(5)).send(any(),anyMap());push.retry(10L);assertEquals("pending",db.queryForObject("SELECT status FROM notice_push_task",String.class));
 }
 @Test void lateDeviceBindingCannotRestoreLoggedOutSession(){
  db.update("UPDATE app_user SET token_version=1 WHERE id=1");assertThrows(BizException.class,()->push.bind(1L,"device1",0));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notice_push_device",Integer.class));
 }
 @Test void workerCancelsInvalidAccountsAndExpiredDeviceBindings(){
  queue();db.update("UPDATE app_user SET token_version=1 WHERE id=1");push.dispatch();assertEquals("cancelled",db.queryForObject("SELECT status FROM notice_push_task",String.class));verify(push,never()).send(any(),anyMap());
 }
 @Test void concurrentWorkersClaimEachTaskOnlyOnce()throws Exception{
  queue();doAnswer(invocation->{Thread.sleep(100);return null;}).when(push).send(any(),anyMap());
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var a=pool.submit(push::dispatch);var b=pool.submit(push::dispatch);a.get();b.get();}
  assertEquals("accepted",db.queryForObject("SELECT status FROM notice_push_task",String.class));verify(push,times(1)).send(any(),anyMap());
 }
}
