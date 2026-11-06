package com.yyx.ciyuantown.business.notice.controller;
import com.yyx.ciyuantown.business.notice.dto.NoticeQueryParam;
import com.yyx.ciyuantown.business.notice.service.*;
import com.yyx.ciyuantown.business.notice.vo.NoticeDetailVO;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/app/notice") @RequiredArgsConstructor
public class AppNoticeController {
 private final NoticeService service;private final NoticeDeliveryService delivery;private final NoticePushService push;
 private Long user(HttpServletRequest r){return (Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID);}
 @GetMapping("/categories")public Result<?> categories(HttpServletRequest r){return Result.success(delivery.summary(user(r)).categories().stream().map(c->new CategoryVO(c.id(),c.name(),c.iconUrl())).toList());}
 @GetMapping("/summary")public Result<?> summary(HttpServletRequest r){return Result.success(delivery.summary(user(r)));}
 @GetMapping("/list")public Result<?> list(HttpServletRequest r,@ModelAttribute NoticeQueryParam p){return Result.success(delivery.list(p,user(r)));}
 @org.springframework.transaction.annotation.Transactional(readOnly=true)
 @GetMapping("/{id}")public Result<NoticeDetailVO> detail(HttpServletRequest r,@PathVariable Long id){
  if(!delivery.canView(id,user(r)))throw new BizException("通知不存在或已下架");
  var vo=service.detail(id,true);vo.setRecipientUserIds(null);vo.setPushTasks(null);vo.setPushEnabled(null);return Result.success(vo);
 }
 @PostMapping("/read/category") @AppLoginRequired public Result<Void> readCategory(HttpServletRequest r,@RequestBody CategoryRead p){delivery.readCategory(user(r),p.categoryId(),p.publicationIds());return Result.success();}
 @PostMapping("/read/one") @AppLoginRequired public Result<Void> readOne(HttpServletRequest r,@RequestBody OneRead p){delivery.readOne(user(r),p.id(),p.publicationId());return Result.success();}
 @PostMapping("/device/bind") @AppLoginRequired public Result<Void> bind(HttpServletRequest r,@RequestBody Device p){push.bind(user(r),p.cid(),(Integer)r.getAttribute(com.yyx.ciyuantown.common.interceptor.AppAuthInterceptor.ATTR_TOKEN_VERSION));return Result.success();}
 @PostMapping("/device/unbind") @AppLoginRequired public Result<Void> unbind(HttpServletRequest r,@RequestBody Device p){push.unbind(user(r),p.cid());return Result.success();}
 @GetMapping("/push-config")public Result<?> pushConfig(){return Result.success(java.util.Map.of("enabled",push.masked().enabled()));}
 public record CategoryVO(Long id,String name,String iconUrl){}
 public record CategoryRead(Long categoryId,List<Long> publicationIds){}
 public record OneRead(Long id,Long publicationId){}
 public record Device(String cid){}
}
