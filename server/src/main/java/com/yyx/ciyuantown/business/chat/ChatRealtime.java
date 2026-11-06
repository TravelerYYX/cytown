package com.yyx.ciyuantown.business.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.*;
import java.util.concurrent.*;

@Component @RequiredArgsConstructor
public class ChatRealtime extends TextWebSocketHandler {
 private final JdbcTemplate db;
 private final ObjectMapper json;
 private final ConcurrentMap<Long,Set<WebSocketSession>> sessions=new ConcurrentHashMap<>();
 public void notifyAfterCommit(long user){
  if(!TransactionSynchronizationManager.isSynchronizationActive()){notifyUser(user);return;}
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){notifyUser(user);}});
 }
 private void notifyUser(long user){for(var s:sessions.getOrDefault(user,Set.of()))try{synchronized(s){if(s.isOpen())s.sendMessage(new TextMessage("{\"type\":\"sync\"}"));}}catch(Exception ignored){try{s.close();}catch(Exception ignoredAgain){}}}
 @Override public void afterConnectionEstablished(WebSocketSession s)throws Exception{
  String ticket=UriComponentsBuilder.fromUri(s.getUri()).build().getQueryParams().getFirst("ticket");
  if(ticket==null||!ticket.matches("[a-f0-9]{64}")){s.close(CloseStatus.POLICY_VIOLATION);return;}
  var rows=db.queryForList("SELECT t.* FROM chat_ticket t JOIN app_user u ON u.id=t.user_id WHERE ticket=? AND expires_at>? AND u.status=0 AND (u.deleted=FALSE OR u.deleted IS NULL) AND t.token_version=COALESCE(u.token_version,0)",ticket,System.currentTimeMillis());
  if(rows.isEmpty()||db.update("DELETE FROM chat_ticket WHERE ticket=?",ticket)!=1){s.close(CloseStatus.POLICY_VIOLATION);return;}
  var row=rows.getFirst();long user=((Number)row.get("user_id")).longValue();s.getAttributes().put("user",user);s.getAttributes().put("version",row.get("token_version"));
  sessions.computeIfAbsent(user,x->ConcurrentHashMap.newKeySet()).add(s);s.sendMessage(new TextMessage("{\"type\":\"sync\"}"));
 }
 @Override protected void handleTextMessage(WebSocketSession s,TextMessage m)throws Exception{
  if(m.getPayloadLength()>128){s.close(CloseStatus.TOO_BIG_TO_PROCESS);return;}
  long user=(Long)s.getAttributes().get("user");
  if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND status=0 AND (deleted=FALSE OR deleted IS NULL) AND COALESCE(token_version,0)=?",Integer.class,user,s.getAttributes().get("version"))!=1){s.close(CloseStatus.POLICY_VIOLATION);return;}
  synchronized(s){s.sendMessage(new TextMessage("{\"type\":\"pong\"}"));}
 }
 @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status){Object user=s.getAttributes().get("user");if(user instanceof Long id){var group=sessions.get(id);if(group!=null){group.remove(s);if(group.isEmpty())sessions.remove(id,group);}}}
 @Configuration @EnableWebSocket @RequiredArgsConstructor
 public static class Sockets implements WebSocketConfigurer {
  private final ChatRealtime realtime;
  @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(realtime,"/app/chat/socket").setAllowedOriginPatterns("*");}
 }
}
