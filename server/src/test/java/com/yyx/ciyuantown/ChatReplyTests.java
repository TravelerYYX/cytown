package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.chat.*;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(ChatTests.Config.class)
class ChatReplyTests {
 @Autowired JdbcTemplate db;@Autowired ChatService chat;@Autowired ObjectMapper json;
 @BeforeEach void setup()throws Exception{var fixture=new ChatTests();fixture.db=db;fixture.setup();db.execute("INSERT INTO user_follow(follower_id,followee_id) VALUES(1,2),(1,3)");}
 ChatService.Message text(long from,long to,String client,String content){return chat.send(from,new ChatService.Send(""+to,client,"text",content,null));}
 ChatService.Message reply(long from,long to,String client,String target){return chat.send(from,new ChatService.Send(""+to,client,"text","回复正文",null,target));}
 @Test void repliesAreServerDerivedPersistedAndDoNotNest(){
  var original=text(1,2,"original_1234","原文");var response=reply(2,1,"reply_123456",original.id());
  assertEquals(original.id(),response.reply().messageId());assertEquals("A",response.reply().senderName());assertEquals("原文",response.reply().summary());assertEquals("available",response.reply().status());assertEquals(original.sentAt(),response.reply().sentAt());
  assertEquals(response.reply(),chat.message(1,Long.parseLong(response.id())).reply());assertEquals(response.reply(),chat.history(1,Long.parseLong(response.conversationId()),0,50).records().getFirst().reply());
  var nested=reply(1,2,"reply_nested_1",response.id());assertEquals("回复正文",nested.reply().summary());assertEquals(response.id(),nested.reply().messageId());
 }
 @Test void ownMessagesAndAllMediaCanBeQuoted(){
  var own=text(1,2,"own_text_1234","自己的消息");assertEquals("1",reply(1,2,"own_reply_123",own.id()).reply().senderId());
  for(String kind:new String[]{"image","emoji","video"}){
   var fixture=new ChatTests();fixture.db=db;long asset=fixture.asset(kind);
   var source=chat.send(1,new ChatService.Send("2","media_source_"+kind,kind,"",""+asset));
   var quote=reply(2,1,"media_reply_"+kind,source.id());assertEquals(kind,quote.reply().kind());assertEquals(kind.equals("image")?"[图片]":kind.equals("emoji")?"[表情]":"[视频]",quote.reply().summary());
  }
 }
 @Test void targetsMustBeVisibleAndInTheSameConversation(){
  var other=text(1,3,"other_chat_123","other");assertThrows(BizException.class,()->reply(1,2,"wrong_chat_123",other.id()));assertThrows(BizException.class,()->reply(1,2,"missing_12345","999999"));
  var revoked=text(1,2,"revoked_12345","撤销内容");chat.revoke(1,Long.parseLong(revoked.id()));assertThrows(BizException.class,()->reply(2,1,"revoked_reply_1",revoked.id()));
  var cleared=text(1,2,"cleared_12345","清空内容");db.update("UPDATE chat_conversation SET cleared_through=? WHERE id=?",Long.parseLong(cleared.id()),Long.parseLong(cleared.conversationId()));assertThrows(BizException.class,()->reply(2,1,"cleared_reply_1",cleared.id()));
 }
 @Test void revocationDeletionAndClearNeverExposeOldQuotedText(){
  var source=text(1,2,"secret_123456","不能泄露");var response=reply(2,1,"secret_reply_1",source.id());chat.revoke(1,Long.parseLong(source.id()));
  var revoked=chat.message(2,Long.parseLong(response.id())).reply();assertEquals("revoked",revoked.status());assertEquals("",revoked.summary());
  db.update("DELETE FROM chat_message WHERE id=?",Long.parseLong(source.id()));var deleted=chat.message(2,Long.parseLong(response.id())).reply();assertEquals("deleted",deleted.status());assertEquals("",deleted.summary());
  var source2=text(1,2,"secret2_12345","清空原文");var response2=reply(2,1,"secret2_reply_1",source2.id());db.update("UPDATE chat_conversation SET cleared_through=? WHERE id=?",Long.parseLong(source2.id()),Long.parseLong(source2.conversationId()));
  assertEquals("deleted",chat.message(2,Long.parseLong(response2.id())).reply().status());assertEquals("",chat.message(2,Long.parseLong(response2.id())).reply().summary());
 }
 @Test void retriesAreIdempotentEvenWhenTheOriginalWasRevoked(){
  var source=text(1,2,"retry_source_1","原文");var response=reply(2,1,"retry_reply_1",source.id());chat.revoke(1,Long.parseLong(source.id()));
  var retry=reply(2,1,"retry_reply_1",source.id());assertEquals(response.id(),retry.id());assertEquals("revoked",retry.reply().status());assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM chat_message",Integer.class));
 }
 @Test void unicodeSummariesAreBoundedAndLegacyJsonStillWorks()throws Exception{
  var source=text(1,2,"unicode_12345","😀".repeat(201));var response=reply(2,1,"unicode_reply_1",source.id());assertEquals(200,response.reply().summary().codePointCount(0,response.reply().summary().length()));
  var request=json.readValue("{\"peerId\":\"2\",\"clientId\":\"legacy_12345\",\"kind\":\"text\",\"content\":\"旧客户端\"}",ChatService.Send.class);assertNull(chat.send(1,request).reply());
  var newRequest=json.readValue("{\"peerId\":\"2\",\"clientId\":\"new_json_12345\",\"kind\":\"text\",\"content\":\"新客户端\",\"replyToId\":\""+source.id()+"\"}",ChatService.Send.class);assertNotNull(chat.send(1,newRequest).reply());
 }
}
