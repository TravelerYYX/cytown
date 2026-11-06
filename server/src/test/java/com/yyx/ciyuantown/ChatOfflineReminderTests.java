package com.yyx.ciyuantown;

import com.yyx.ciyuantown.business.chat.*;
import com.yyx.ciyuantown.business.notice.service.NoticePushService;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ChatTests.Config.class)
class ChatOfflineReminderTests {
 @Autowired JdbcTemplate db;
 @Autowired ChatService chat;
 @Autowired ChatSettings settings;
 ChatTests fixture;

 @BeforeEach void setup() throws Exception {
  fixture=new ChatTests();fixture.db=db;fixture.chat=chat;fixture.settings=settings;fixture.setup();
  db.update("INSERT INTO notice_push_device VALUES('offline_device',2,0)");fixture.follow(1,2);
 }
 ChatService.Message send(){return fixture.text(1,2,"offline_"+UUID.randomUUID());}
 int queued(){return db.queryForObject("SELECT COUNT(*) FROM chat_push_task WHERE state='pending'",Integer.class);}

 @Test void defaultsOffAndDoesNotChangeChatDeliveryOrUnread(){
  assertFalse(settings.offlineReminder().enabled());var message=send();assertEquals(0,queued());
  assertEquals(message.id(),chat.history(2,Long.parseLong(message.conversationId()),0,20).records().getFirst().id());
  assertEquals(1,chat.conversations(2).getFirst().unread());assertFalse(chat.sync(2,0).events().isEmpty());
 }
 @Test void switchPersistsIndependentlyFromMediaAndRevokeSettings(){
  settings.saveOfflineReminder(new ChatSettings.OfflineReminder(true));
  settings.save(new ChatSettings.Config(10,ChatSettings.defaults().images(),ChatSettings.defaults().videos()));
  assertTrue(settings.offlineReminder().enabled());assertEquals(1,queuedAfterSend());
  settings.saveOfflineReminder(new ChatSettings.OfflineReminder(false));
  assertEquals(10,settings.get().revokeSeconds());assertEquals("0",db.queryForObject("SELECT config_value FROM system_config WHERE config_key='chat_offline_reminder_enabled'",String.class));
 }
 int queuedAfterSend(){send();return queued();}
 @Test void disabledSwitchAppliesToImagesEmojiAndVideoAsWellAsText(){
  send();
  for(String kind:new String[]{"image","emoji","video"}){
   long asset=fixture.asset(kind);
   chat.send(1,new ChatService.Send("2","offline_"+UUID.randomUUID(),kind,"",""+asset));
  }
  assertEquals(0,queued());assertEquals(4,chat.conversations(2).getFirst().unread());
 }
 @Test void disablingCancelsQueuedAndLeasedTasksAndReenableOnlyQueuesNewMessages(){
  settings.saveOfflineReminder(new ChatSettings.OfflineReminder(true));
  var pending=send();var sending=send();var accepted=send();
  db.update("UPDATE chat_push_task SET state='sending',lease_until=9999999999999 WHERE message_id=?",sending.id());
  db.update("UPDATE chat_push_task SET state='accepted' WHERE message_id=?",accepted.id());
  settings.saveOfflineReminder(new ChatSettings.OfflineReminder(false));
  assertEquals(0,queued());assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM chat_push_task WHERE state='cancelled' AND lease_until=0",Integer.class));
  assertEquals("accepted",db.queryForObject("SELECT state FROM chat_push_task WHERE message_id=?",String.class,accepted.id()));
  send();settings.saveOfflineReminder(new ChatSettings.OfflineReminder(true));send();
  assertEquals(1,queued());assertEquals("cancelled",db.queryForObject("SELECT state FROM chat_push_task WHERE message_id=?",String.class,pending.id()));
 }
 @Test void disabledWorkerCancelsExistingBacklogWithoutCallingPushProvider(){
  settings.saveOfflineReminder(new ChatSettings.OfflineReminder(true));send();
  db.update("UPDATE system_config SET config_value='0' WHERE config_key='chat_offline_reminder_enabled'");
  var provider=mock(NoticePushService.class);new ChatPush(db,provider,chat,settings).dispatch();
  assertEquals(0,queued());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_push_task WHERE state='cancelled'",Integer.class));verifyNoInteractions(provider);
 }
 @Test void globalPushStillControlsDeliveryWhenReminderIsEnabled(){
  settings.saveOfflineReminder(new ChatSettings.OfflineReminder(true));send();
  var provider=mock(NoticePushService.class);when(provider.config()).thenReturn(new NoticePushService.Config(false,"","","",false));
  new ChatPush(db,provider,chat,settings).dispatch();assertEquals(1,queued());verify(provider,never()).send(any(),any());
 }
 @Test void missingValueIsRejectedWithoutChangingStoredSetting(){
  settings.saveOfflineReminder(new ChatSettings.OfflineReminder(true));
  assertThrows(BizException.class,()->settings.saveOfflineReminder(new ChatSettings.OfflineReminder(null)));
  assertTrue(settings.offlineReminder().enabled());
 }
}
