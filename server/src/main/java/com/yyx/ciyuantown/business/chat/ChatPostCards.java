package com.yyx.ciyuantown.business.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Post snapshots are derived from published content, never from client supplied metadata. */
@Service @RequiredArgsConstructor
public class ChatPostCards {
 private final PostService posts;
 private static final ObjectMapper JSON = new ObjectMapper();
 public record Card(String id,String title,String coverUrl,String authorId,String authorName,String authorAvatar,boolean video,String authorAvatarFrame,Long authorAvatarFrameExpiresAt) {}
 public Card snapshot(String postId) {
  posts.requirePost(ChatService.id(postId),true);
  var post=posts.detail(ChatService.id(postId),true);
  if(post==null||!Integer.valueOf(1).equals(post.getStatus()))throw new BizException("帖子不存在或已下架，无法分享");
  var first=post.getMedia()==null||post.getMedia().isEmpty()?null:post.getMedia().getFirst();
  boolean video="video".equals(post.getMode())||first!=null&&"video".equals(first.type());
  String cover=first==null?"":video?first.coverUrl():first.previewUrl()!=null&&!first.previewUrl().isBlank()?first.previewUrl():first.url();
  String title=post.getTitle();if(title==null||title.isBlank())title=post.getContent();
  if(title==null||title.isBlank())title=video?"分享视频":"分享帖子";
  title=title.replaceAll("\\s+"," ").trim();
  if(title.codePointCount(0,title.length())>100)title=title.substring(0,title.offsetByCodePoints(0,100))+"…";
  return new Card(post.getId(),title,cover==null?"":cover,post.getAuthorId(),post.getAuthorNickname(),post.getAuthorAvatar(),video,post.getAuthorAvatarFrame(),post.getAuthorAvatarFrameExpiresAt());
 }
 public static String encode(Card card){try{return JSON.writeValueAsString(card);}catch(Exception e){throw new BizException("帖子分享失败，请重试");}}
 public static Card decode(String content){try{return JSON.readValue(content,Card.class);}catch(Exception e){return null;}}
 public static String summary(String content){var card=decode(content);return "[帖子]"+(card==null?"":card.title());}
}
