package com.yyx.ciyuantown;
import com.yyx.ciyuantown.business.chat.*;
import com.yyx.ciyuantown.business.notice.service.NoticePushService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Opt-in MySQL integration; deliberately refuses the application's normal database. */
@EnabledIfEnvironmentVariable(named="CT_CHAT_PUSH_TEST",matches="true")
@SpringBootTest(properties={"ciyuantown.redis.enabled=false"})
class ChatPushTests {
 @Autowired JdbcTemplate db;@Autowired ChatService chat;@Autowired ChatPush worker;@Autowired ChatSettings settings;
 @MockitoBean NoticePushService push;
 static final String CID="ct_chat_push_fixture";
 @BeforeEach void setup(){assertTrue(db.queryForObject("SELECT DATABASE()",String.class).contains("_chat_e2e_")||db.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_cleanup_"));reset(push);when(push.config()).thenReturn(new NoticePushService.Config(true,"test-app","https://bridge.test.invalid","test-secret-with-at-least-32-characters",true));
  db.update("INSERT INTO app_user(id,qq_number,email,nickname) VALUES(9003,'900000003','push-a@test.local','推送发送者'),(9004,'900000004','push-b@test.local','推送接收者') ON DUPLICATE KEY UPDATE status=0,deleted=FALSE,token_version=0");
  settings.saveOfflineReminder(new ChatSettings.OfflineReminder(true));
  db.update("INSERT IGNORE INTO user_follow(follower_id,followee_id) VALUES(9003,9004)");db.update("DELETE FROM chat_push_task WHERE cid=?",CID);db.update("INSERT INTO notice_push_device(cid,user_id,token_version) VALUES(?,9004,0) ON DUPLICATE KEY UPDATE user_id=9004,token_version=0",CID);
 }
 @AfterEach void cleanup(){db.update("DELETE FROM notice_push_device WHERE cid=?",CID);db.update("DELETE FROM chat_push_task WHERE cid=?",CID);}
 ChatService.Message send(){return chat.send(9003,new ChatService.Send("9004","push_"+UUID.randomUUID(),"text","😄".repeat(100),null));}
 String state(ChatService.Message m){return db.queryForObject("SELECT state FROM chat_push_task WHERE message_id=? AND cid=?",String.class,m.id(),CID);}
 @Test void bridgePayloadIsBoundToRecipientAndAcceptedTaskDoesNotRepeat(){var m=send();worker.dispatch();assertEquals("accepted",state(m));var payload=ArgumentCaptor.forClass(Map.class);verify(push).send(any(),payload.capture());var value=payload.getValue();assertEquals(CID,value.get("cid"));String content=value.get("content").toString();assertEquals(45,content.codePointCount(0,content.length()));var intent=(Map<?,?>)value.get("payload");assertEquals("chat",intent.get("type"));assertEquals(m.id(),intent.get("messageId"));assertEquals(m.conversationId(),intent.get("conversationId"));worker.dispatch();verify(push,times(1)).send(any(),any());}
 @Test void revokedAndReadMessagesCancelQueuedNotifications(){var m=send();chat.revoke(9003,Long.parseLong(m.id()));worker.dispatch();assertEquals("cancelled",state(m));verify(push,never()).send(any(),any());var read=send();chat.read(9004,Long.parseLong(read.conversationId()),Long.parseLong(read.id()));worker.dispatch();assertEquals("cancelled",state(read));}
 @Test void reboundDeviceCannotReceiveOldAccountContent(){var m=send();db.update("UPDATE notice_push_device SET user_id=9003 WHERE cid=?",CID);worker.dispatch();assertEquals("cancelled",state(m));verify(push,never()).send(any(),any());}
 @Test void transientFailureBacksOffAndStopsAfterFiveAttempts(){doThrow(new RuntimeException("bridge offline")).when(push).send(any(),any());var m=send();worker.dispatch();assertEquals("pending",state(m));assertTrue(db.queryForObject("SELECT next_attempt_at FROM chat_push_task WHERE message_id=? AND cid=?",Long.class,m.id(),CID)>System.currentTimeMillis());worker.dispatch();verify(push,times(1)).send(any(),any());db.update("UPDATE chat_push_task SET attempts=4,next_attempt_at=0 WHERE message_id=? AND cid=?",m.id(),CID);worker.dispatch();assertEquals("failed",state(m));}
}
