package com.yyx.ciyuantown;

import com.yyx.ciyuantown.business.chat.ChatService;
import com.yyx.ciyuantown.business.report.UserBlockService;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(UserBlockTests.Config.class)
class UserBlockTests {
    @Configuration @Import(ChatTests.Config.class) static class Config {
        @Bean UserBlockService blocks(JdbcTemplate db,ChatService chat){return new UserBlockService(db,chat);}
    }
    @Autowired JdbcTemplate db;@Autowired ChatService chat;@Autowired UserBlockService blocks;
    @BeforeEach void setup()throws Exception {var fixture=new ChatTests();fixture.db=db;fixture.setup();}
    ChatService.Message send(long from,long to,String client){return chat.send(from,new ChatService.Send(""+to,client,"text","hello",null));}
    @Test void blockIsDirectionalDoesNotUnfollowAndCanBeRemoved() {
        db.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(1,2),(2,1)");
        var old=send(2,1,"before_block");blocks.set(1,2,true);blocks.set(1,2,true);
        assertTrue(blocks.blocked(1L,2));assertFalse(blocks.blocked(2L,1));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_block",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM user_follow",Integer.class));
        for(String kind:List.of("text","image","video","emoji")) {
            var error=assertThrows(BizException.class,()->chat.send(2,new ChatService.Send("1","blocked_"+kind,kind,"hello","123")));
            assertEquals("已被对方拉黑",error.getMessage());
        }
        assertThrows(BizException.class,()->chat.send(2,new ChatService.Send("1","blocked_reply","text","reply",null,old.id())));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_message",Integer.class));
        send(1,2,"reverse_allowed");assertEquals(old.id(),chat.message(1,Long.parseLong(old.id())).id());
        blocks.set(1,2,false);blocks.set(1,2,false);send(2,1,"blocked_text");
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM chat_message",Integer.class));
    }
    @Test void blockedAttemptDoesNotCreatePushOrDeliveryEvents() {
        blocks.set(1,2,true);long events=db.queryForObject("SELECT COUNT(*) FROM chat_event",Long.class);
        assertThrows(BizException.class,()->send(2,1,"no_delivery_1"));
        assertEquals(events,db.queryForObject("SELECT COUNT(*) FROM chat_event",Long.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_push_task",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_message",Integer.class));
    }
    @Test void blockRejectsSelfAndMissingUsers() {
        assertThrows(BizException.class,()->blocks.set(1,1,true));assertThrows(BizException.class,()->blocks.set(1,999,true));
    }
    @Test void concurrentSendAndBlockLeaveNoDeliveriesAfterBlockReturns()throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var sending=pool.submit(()->{start.await();try{send(2,1,"racing_send_1");}catch(BizException ignored){}return true;});
            var blocking=pool.submit(()->{start.await();return blocks.set(1,2,true);});start.countDown();
            assertTrue(blocking.get(10,TimeUnit.SECONDS));sending.get(10,TimeUnit.SECONDS);
            long count=db.queryForObject("SELECT COUNT(*) FROM chat_message",Long.class);
            assertThrows(BizException.class,()->send(2,1,"after_block_1"));assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM chat_message",Long.class));
        }finally{pool.shutdownNow();}
    }
}
