package com.yyx.ciyuantown;

import com.yyx.ciyuantown.business.chat.*;
import com.yyx.ciyuantown.business.appuser.service.*;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.ResourceCleanupService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ChatAdministrationTests.Config.class)
class ChatAdministrationTests {
    @Configuration @Import(ChatTests.Config.class) static class Config {
        @Bean LoginAccountService accounts(){var service=mock(LoginAccountService.class);when(service.requireSuper(any())).thenAnswer(call->{if(!Long.valueOf(100).equals(call.getArgument(0)))throw new BizException("仅超级管理员可执行此操作");return null;});return service;}
        @Bean ResourceCleanupService cleanup(){return mock(ResourceCleanupService.class);}
        @Bean AppUserService users(){return mock(AppUserService.class);}
        @Bean ChatAdministration admin(JdbcTemplate db,ChatService chat,ChatPolicy policy,LoginAccountService accounts,ResourceCleanupService cleanup,PlatformTransactionManager transactions,AppUserService users){return new ChatAdministration(db,chat,policy,accounts,cleanup,transactions,users);}
    }
    @Autowired JdbcTemplate db;@Autowired ChatService chat;@Autowired ChatAdministration admin;@Autowired ChatPolicy policy;@Autowired ResourceCleanupService cleanup;
    @BeforeEach void setup()throws Exception{
        var fixture=new ChatTests();fixture.db=db;fixture.setup();
        db.execute("ALTER TABLE app_user ADD username VARCHAR(40)");db.execute("ALTER TABLE app_user ADD qq_number VARCHAR(40)");db.execute("ALTER TABLE app_user ADD official_admin_id BIGINT");
        db.execute("CREATE TABLE admin_user(id BIGINT PRIMARY KEY,username VARCHAR(40))");
        db.execute("UPDATE app_user SET username=CONCAT('user_',id),qq_number=CONCAT('90000',id)");
        db.execute("INSERT INTO user_follow(follower_id,followee_id) VALUES(1,2),(1,3)");reset(cleanup);
    }
    ChatService.Message send(long sender,long peer,String client){return chat.send(sender,new ChatService.Send(""+peer,client,"text","message "+client,null));}
    long c(){return Long.parseLong(send(1,2,"initial_client").conversationId());}
    @Test void listsOnlyRealConversationsAndUsesEffectiveAccounts(){
        chat.relationship(1,3);assertTrue(admin.list(100L,"",1,20).records().isEmpty());long c=c();
        assertEquals(1,admin.list(100L,"user_2",1,20).total());assertEquals("A",admin.detail(100L,c).a().nickname());
        db.execute("INSERT INTO admin_user VALUES(20,'official_account')");db.execute("UPDATE app_user SET official_admin_id=20,username=NULL WHERE id=2");
        assertEquals("official_account",admin.list(100L,"official_account",1,20).records().getFirst().b().account());assertTrue(admin.list(100L,"absent",1,20).records().isEmpty());
        assertThrows(BizException.class,()->admin.list(100L,"",0,20));assertThrows(BizException.class,()->admin.list(100L,"",1,101));
    }
    @Test void superPermissionIsCheckedOnReadWriteAndMedia(){
        long c=c();assertThrows(BizException.class,()->admin.list(101L,"",1,20));assertThrows(BizException.class,()->admin.detail(null,c));assertThrows(BizException.class,()->admin.history(101L,c,0,0,50));assertThrows(BizException.class,()->admin.assetAccess(101L,1));assertThrows(BizException.class,()->admin.delete(101L,c,null));assertThrows(BizException.class,()->admin.rule(101L,new ChatAdministration.Rule("1","0","block",null)));
    }
    @Test void historyHasBoundedStableBidirectionalPagesAndDoesNotRead(){
        long c=c();for(int i=0;i<124;i++)send(1,2,"history_client_"+i);
        var latest=admin.history(100L,c,0,0,50);assertEquals(50,latest.records().size());assertFalse(latest.older().isEmpty());assertTrue(latest.newer().isEmpty());
        var older=admin.history(100L,c,Long.parseLong(latest.older()),0,50);assertEquals(50,older.records().size());assertEquals(latest.records(),admin.history(100L,c,0,Long.parseLong(older.newer()),50).records());
        var earliest=admin.history(100L,c,Long.parseLong(older.older()),0,50);assertEquals(25,earliest.records().size());assertTrue(earliest.older().isEmpty());assertEquals(0,db.queryForObject("SELECT SUM(read_message_id) FROM chat_member",Long.class));
    }
    @Test void permanentGlobalAndPairRestrictionsRemainIndependent(){
        long c=c();admin.rule(100L,new ChatAdministration.Rule("1","0","block",null));assertThrows(BizException.class,()->send(1,2,"blocked_text"));assertDoesNotThrow(()->send(2,1,"allowed_inbound"));assertFalse(chat.history(1,c,0,50).records().isEmpty());
        admin.rule(100L,new ChatAdministration.Rule("1","0","mute",10L));admin.rule(100L,new ChatAdministration.Rule("1",""+c,"mute",60L));admin.rule(100L,new ChatAdministration.Rule("1","0","unblock",null));assertThrows(BizException.class,()->send(1,2,"still_muted"));
        admin.rule(100L,new ChatAdministration.Rule("1","0","unmute",null));assertDoesNotThrow(()->send(1,3,"unrelated_chat"));assertThrows(BizException.class,()->send(1,2,"pair_muted_text"));
        db.update("UPDATE chat_restriction SET muted_until=? WHERE user_id=1",System.currentTimeMillis()-1);assertDoesNotThrow(()->send(1,2,"expired_mute"));
        assertThrows(BizException.class,()->admin.rule(100L,new ChatAdministration.Rule("3",""+c,"mute",1L)));assertThrows(BizException.class,()->admin.rule(100L,new ChatAdministration.Rule("1","0","mute",0L)));
    }
    @Test void everyMessageKindIsBlockedAtCommit(){long c=c();admin.rule(100L,new ChatAdministration.Rule("1",""+c,"mute",10L));for(String kind:List.of("text","image","emoji","video"))assertThrows(BizException.class,()->chat.send(1,new ChatService.Send("2","blocked_"+kind,kind,"content","1")));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_message",Integer.class));}
    @Test void deleteRemovesBodyReceiptsAndPushAndCannotBeRetried(){
        var m=send(1,2,"delete_client");long id=Long.parseLong(m.id()),c=Long.parseLong(m.conversationId());db.update("INSERT INTO chat_receipt(operation_id,message_id,user_id,kind,created_at) VALUES('operation',?,2,'saved',0)",id);
        var job=admin.delete(100L,c,id);assertEquals("completed",job.state());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_message",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_receipt",Integer.class));assertTrue(chat.message(2,id).deleted());assertEquals("",chat.message(2,id).content());assertThrows(BizException.class,()->send(1,2,"delete_client"));assertEquals(job.id(),admin.delete(100L,c,id).id());assertTrue(chat.sync(2,0).events().stream().anyMatch(e->e.kind().equals("deleted")));
    }
    @Test void clearHidesImmediatelyAndPreservesNewMessagesAndRelationship(){
        long c=c();for(int i=0;i<205;i++)send(1,2,"clear_client_"+i);db.execute("DELETE FROM user_follow");var job=admin.delete(100L,c,null);
        assertEquals("cleaning",job.state());assertTrue(chat.history(2,c,0,50).records().isEmpty());assertTrue(admin.list(100L,"",1,20).records().isEmpty());assertThrows(BizException.class,()->send(1,2,"initial_client"));
        var newMessage=send(2,1,"after_cutoff");admin.process(job.id());assertFalse(admin.job(100L,job.id()).state().equals("completed"));admin.process(job.id());admin.process(job.id());assertEquals("completed",admin.job(100L,job.id()).state());
        assertEquals(List.of(newMessage.id()),chat.history(1,c,0,50).records().stream().map(ChatService.Message::id).toList());assertThrows(BizException.class,()->send(1,2,"initial_client"));assertEquals(1,admin.list(100L,"",1,20).total());
    }
    @Test void administratorsCanDeleteHistoryOfBannedUsers(){var m=send(1,2,"banned_history");db.execute("UPDATE app_user SET status=1 WHERE id=2");assertDoesNotThrow(()->admin.delete(100L,Long.parseLong(m.conversationId()),Long.parseLong(m.id())));}
    @Test void mediaDeletionForcesIndependentVideoAndCoverCleanup(){
        long c=c();db.update("INSERT INTO chat_asset(id,owner_id,kind,state,version,manifest_json,config_json,created_at,cover_id) VALUES(10,1,'video','ready','v1','{}','{}',0,11),(11,1,'image','ready','cover','{}','{}',0,NULL)");var m=chat.send(1,new ChatService.Send("2","media_delete_1","video","","10"));var job=admin.delete(100L,c,Long.parseLong(m.id()));verify(cleanup).forceRetireChat(10L,job.id());verify(cleanup).forceRetireChat(11L,job.id());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_asset",Integer.class));assertThrows(BizException.class,()->admin.assetAccess(100L,10));
    }
    @Test void concurrentSendAndClearHaveOneDeterministicCutoff()throws Exception{
        long c=c();var gate=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var send=pool.submit(()->{gate.await();return send(2,1,"race_new_message");});var clear=pool.submit(()->{gate.await();return admin.delete(100L,c,null);});gate.countDown();var m=send.get(15,TimeUnit.SECONDS);var job=clear.get(15,TimeUnit.SECONDS);long cutoff=db.queryForObject("SELECT cleared_through FROM chat_conversation WHERE id=?",Long.class,c);admin.process(job.id());assertEquals(Long.parseLong(m.id())>cutoff,!chat.history(1,c,0,50).records().isEmpty());}
    }
}
