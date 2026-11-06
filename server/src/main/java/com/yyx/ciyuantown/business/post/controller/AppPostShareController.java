package com.yyx.ciyuantown.business.post.controller;

import com.yyx.ciyuantown.business.chat.ChatService;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.business.post.service.PostShareSettings;
import com.yyx.ciyuantown.business.post.vo.PostDetailVO;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

@RestController @RequiredArgsConstructor @RequestMapping("/app/share")
public class AppPostShareController {
 private final PostService posts;
 private final PostShareSettings settings;
 public record Link(String url,String text) {}
 private PostDetailVO published(long id){posts.requirePost(id,true);var post=posts.detail(id,true);if(post==null||!Integer.valueOf(1).equals(post.getStatus()))throw new BizException("帖子不存在或已下架");return post;}
 private static String plain(String value){return value==null?"":value.replaceAll("\\s+"," ").trim();}
 private static String excerpt(String value,int count){String text=plain(value);return text.codePointCount(0,text.length())>count?text.substring(0,text.offsetByCodePoints(0,count))+"...":text;}
 @GetMapping("/posts/{id}") public Result<Link> link(@PathVariable String id){
  long postId=ChatService.id(id);var post=published(postId);
  String base=settings.get().baseUrl();if(base.isBlank())base=ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
  String url=base+"/app/share/p/"+Long.toString(postId,36);
  String title=excerpt(post.getTitle(),40),body=excerpt(post.getContent(),28);
  String prefix=title.isEmpty()?body:title+(body.isEmpty()||body.equals(title)?"":" "+body);
  if(prefix.isEmpty())prefix="分享一篇精彩帖子";
  return Result.success(new Link(url,prefix+" "+url+" 打开【次元町】，这篇帖子超精彩！"));
 }
 private static String html(String value){return HtmlUtils.htmlEscape(value==null?"":value);}
 private static String safeMedia(String url){if(url==null)return "";return url.startsWith("/")&&!url.startsWith("//")||url.startsWith("https://")||url.startsWith("http://")?html(url):"";}
 @GetMapping(value="/p/{code}",produces="text/html;charset=UTF-8") public ResponseEntity<String> page(@PathVariable String code){
  PostDetailVO post;
  try{if(!code.matches("[0-9a-z]{1,13}"))throw new IllegalArgumentException();long id=Long.parseLong(code,36);if(id<=0)throw new IllegalArgumentException();post=published(id);}catch(IllegalArgumentException|BizException e){return response(404,"<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>帖子不可用 · 次元町</title><body><p>帖子不存在或已下架</p></body></html>");}
  StringBuilder media=new StringBuilder();
  if(post.getMedia()!=null)for(var item:post.getMedia()){
   if("image".equals(item.type())){String source=safeMedia(item.previewUrl()==null||item.previewUrl().isBlank()?item.url():item.previewUrl());if(!source.isEmpty())media.append("<img loading=\"lazy\" alt=\"帖子图片\" src=\"").append(source).append("\">");}
   else if("video".equals(item.type())){String source=safeMedia(item.url());if(!source.isEmpty())media.append("<video controls preload=\"none\" playsinline poster=\"").append(safeMedia(item.coverUrl())).append("\" src=\"").append(source).append("\"></video>");}
  }
  String title=plain(post.getTitle());if(title.isEmpty())title="分享帖子";
  return response(200,"<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+html(title)+" · 次元町</title><style>body{margin:0;background:#f6f7f9;color:#252a34;font-family:system-ui,sans-serif}main{max-width:640px;margin:auto;background:white;min-height:100vh}header{padding:20px 24px;border-bottom:1px solid #f0f0f0;color:#0099ff;font-weight:700}article{padding:24px}h1{font-size:24px;line-height:1.5}p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}small{color:#939aa4}img,video{display:block;width:100%;height:auto;margin:18px 0;border-radius:12px}footer{padding:24px;color:#939aa4;font-size:13px}</style></head><body><main><header>次元町</header><article><small>"+html(post.getAuthorNickname())+"</small><h1>"+html(title)+"</h1>"+media+"<p>"+html(post.getContent())+"</p></article><footer>打开【次元町】，发现更多精彩内容</footer></main></body></html>");
 }
 private static ResponseEntity<String> response(int status,String body){return ResponseEntity.status(status).contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; img-src 'self' http: https: data:; media-src 'self' http: https:; frame-ancestors 'none'; base-uri 'none'").body(body);}
}
