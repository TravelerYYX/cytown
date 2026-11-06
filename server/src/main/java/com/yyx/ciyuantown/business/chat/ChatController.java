package com.yyx.ciyuantown.business.chat;

import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.interceptor.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController @AppLoginRequired @RequestMapping("/app/chat") @RequiredArgsConstructor
public class ChatController {
 private final ChatService chat;private final ChatMedia media;private final ChatVault vault;private final JdbcTemplate db;
 private long user(HttpServletRequest r){return (Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID);}
 public record Read(String messageId) {}
 public record Open(String peerId) {}
 @PostMapping("/conversations") public Result<ChatService.Conversation> open(HttpServletRequest r,@RequestBody Open p){return Result.success(chat.open(user(r),ChatService.id(p.peerId())));}
 @GetMapping("/conversations/{id}") public Result<ChatService.Conversation> conversation(HttpServletRequest r,@PathVariable String id){return Result.success(chat.conversation(user(r),ChatService.id(id)));}
 public record Receipt(String operationId,String kind,String action) {}
 public record Emoji(String emojiId) {}
 @GetMapping("/conversations") public Result<List<ChatService.Conversation>> conversations(HttpServletRequest r){return Result.success(chat.conversations(user(r)));}
 @GetMapping("/conversations/{id}/messages") public Result<ChatService.History> history(HttpServletRequest r,@PathVariable String id,@RequestParam(defaultValue="0")long before,@RequestParam(defaultValue="50")int limit){return Result.success(chat.history(user(r),ChatService.id(id),before,limit));}
 @GetMapping("/messages/{id}") public Result<ChatService.Message> message(HttpServletRequest r,@PathVariable String id){return Result.success(chat.message(user(r),ChatService.id(id)));}
 @PostMapping("/messages") public Result<ChatService.Message> send(HttpServletRequest r,@RequestBody ChatService.Send p){return Result.success(chat.send(user(r),p));}
 @PostMapping("/conversations/{id}/read") public Result<Void> read(HttpServletRequest r,@PathVariable String id,@RequestBody Read p){chat.read(user(r),ChatService.id(id),ChatService.id(p.messageId()));return Result.success();}
 @PostMapping("/messages/{id}/revoke") public Result<ChatService.Message> revoke(HttpServletRequest r,@PathVariable String id){return Result.success(chat.revoke(user(r),ChatService.id(id)));}
 @PostMapping("/messages/{id}/receipt") public Result<ChatService.Operation> receipt(HttpServletRequest r,@PathVariable String id,@RequestBody Receipt p){return Result.success(chat.operation(user(r),ChatService.id(id),p.operationId(),p.kind(),p.action()));}
 @GetMapping("/sync") public Result<ChatService.Sync> sync(HttpServletRequest r,@RequestParam(defaultValue="0")long cursor){return Result.success(chat.sync(user(r),cursor));}
 @PostMapping("/ticket") public Result<Map<String,String>> ticket(HttpServletRequest r){return Result.success(Map.of("ticket",chat.ticket(user(r),(Integer)r.getAttribute(AppAuthInterceptor.ATTR_TOKEN_VERSION))));}
 @PostMapping("/assets/image") public Result<ChatService.Asset> image(HttpServletRequest r,@RequestParam MultipartFile file,@RequestParam(defaultValue="false")boolean original){return Result.success(media.image(user(r),file,original,"image"));}
 @PostMapping("/assets/emoji") public Result<ChatService.Asset> emoji(HttpServletRequest r,@RequestBody Emoji p){return Result.success(media.emoji(user(r),ChatService.id(p.emojiId())));}
 @PostMapping("/assets/video") public Result<ChatService.Asset> video(HttpServletRequest r,@RequestParam MultipartFile file){return Result.success(media.video(user(r),file));}
 @PostMapping("/assets/{id}/retry") public Result<ChatService.Asset> retry(HttpServletRequest r,@PathVariable String id){return Result.success(media.retry(user(r),ChatService.id(id)));}
 @GetMapping("/assets/{id}") public Result<ChatService.Asset> asset(HttpServletRequest r,@PathVariable String id){long asset=ChatService.id(id);media.access(user(r),asset);return Result.success(chat.asset(asset));}
 @GetMapping("/emojis") public Result<List<Map<String,Object>>> emojis(){return Result.success(db.queryForList("SELECT e.id,e.name,e.image_url AS imageUrl,g.id AS groupId,g.name AS groupName FROM publish_emoji e JOIN emoji_group g ON g.id=e.group_id WHERE e.enabled=TRUE AND g.enabled=TRUE ORDER BY g.sort_order,g.id,e.sort_order,e.id"));}
 @RequestMapping(value="/assets/{id}/content",method={RequestMethod.GET,RequestMethod.HEAD})
 public ResponseEntity<StreamingResponseBody> content(HttpServletRequest r,@PathVariable String id){
  long asset=ChatService.id(id),owner=user(r);media.access(owner,asset);var a=chat.assetRow(asset);if(!"ready".equals(a.get("state")))throw new BizException("媒体尚未处理完成");
  var manifest=vault.parse(a.get("manifest_json").toString());long size=manifest.size(),start=0,end=size-1;String range=r.getHeader("Range");
  if(range!=null){try{if(!range.matches("bytes=\\d*-\\d*"))throw new IllegalArgumentException();String[] p=range.substring(6).split("-",-1);if(p[0].isEmpty()){start=Math.max(0,size-Long.parseLong(p[1]));}else{start=Long.parseLong(p[0]);if(!p[1].isEmpty())end=Math.min(end,Long.parseLong(p[1]));}if(start<0||start>=size||end<start)throw new IllegalArgumentException();}catch(Exception e){return ResponseEntity.status(416).header("Content-Range","bytes */"+size).build();}}
  long from=start,to=end;StreamingResponseBody body=out->{try{vault.range(manifest,from,to,out,()->media.access(owner,asset));}catch(Exception e){throw new java.io.IOException("Private media stream unavailable",e);}};
  var builder=ResponseEntity.status(range==null?200:206).contentType(MediaType.parseMediaType(a.get("mime").toString())).contentLength(end-start+1).header("Accept-Ranges","bytes").header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=\"chat-"+asset+"."+a.get("extension")+"\"");
  if(range!=null)builder.header("Content-Range","bytes "+start+"-"+end+"/"+size);return builder.body("HEAD".equals(r.getMethod())?null:body);
 }
}
