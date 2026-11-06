package com.yyx.ciyuantown.business.chat;

import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController @RequestMapping("/chat-admin") @RequiredArgsConstructor
public class ChatManagementController {
    private final ChatAdministration admin;
    private final ChatService chat;
    private final ChatVault vault;
    private Long operator(HttpServletRequest request){return (Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID);}
    @GetMapping("/conversations") public Result<ChatAdministration.Page> list(HttpServletRequest r,@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return Result.success(admin.list(operator(r),keyword,pageNum,pageSize));}
    @GetMapping("/conversations/{id}") public Result<ChatAdministration.Conversation> detail(HttpServletRequest r,@PathVariable String id){return Result.success(admin.detail(operator(r),ChatService.id(id)));}
    @GetMapping("/conversations/{id}/messages") public Result<ChatAdministration.History> history(HttpServletRequest r,@PathVariable String id,@RequestParam(defaultValue="0")long before,@RequestParam(defaultValue="0")long after,@RequestParam(defaultValue="50")int limit){return Result.success(admin.history(operator(r),ChatService.id(id),before,after,limit));}
    @PostMapping("/conversations/{id}/messages/{message}/delete") public Result<ChatAdministration.Job> delete(HttpServletRequest r,@PathVariable String id,@PathVariable String message){return Result.success(admin.delete(operator(r),ChatService.id(id),ChatService.id(message)));}
    @PostMapping("/conversations/{id}/clear") public Result<ChatAdministration.Job> clear(HttpServletRequest r,@PathVariable String id){return Result.success(admin.delete(operator(r),ChatService.id(id),null));}
    @GetMapping("/conversations/{id}/jobs") public Result<java.util.List<ChatAdministration.Job>> jobs(HttpServletRequest r,@PathVariable String id){return Result.success(admin.jobs(operator(r),ChatService.id(id)));}
    @GetMapping("/jobs/{id}") public Result<ChatAdministration.Job> job(HttpServletRequest r,@PathVariable String id){return Result.success(admin.job(operator(r),id));}
    @PostMapping("/restrictions") public Result<Void> rule(HttpServletRequest r,@RequestBody ChatAdministration.Rule rule){admin.rule(operator(r),rule);return Result.success();}
    public record Status(String userId,int status) {}
    @PostMapping("/accounts/status") public Result<Void> status(HttpServletRequest r,@RequestBody Status input){admin.status(operator(r),ChatService.id(input.userId()),input.status());return Result.success();}
    @GetMapping("/assets/{id}") public Result<ChatService.Asset> asset(HttpServletRequest r,@PathVariable String id){long asset=ChatService.id(id);admin.assetAccess(operator(r),asset);return Result.success(chat.asset(asset));}
    @RequestMapping(value="/assets/{id}/content",method={RequestMethod.GET,RequestMethod.HEAD})
    public ResponseEntity<StreamingResponseBody> content(HttpServletRequest r,@PathVariable String id){
        long asset=ChatService.id(id);Long operator=operator(r);admin.assetAccess(operator,asset);var row=chat.assetRow(asset);
        if(!"ready".equals(row.get("state")))throw new BizException("媒体尚未处理完成");
        var manifest=vault.parse(row.get("manifest_json").toString());long size=manifest.size(),start=0,end=size-1;String range=r.getHeader("Range");
        if(range!=null){try{if(!range.matches("bytes=\\d*-\\d*"))throw new IllegalArgumentException();String[] pair=range.substring(6).split("-",-1);if(pair[0].isEmpty()){long suffix=Long.parseLong(pair[1]);if(suffix<=0)throw new IllegalArgumentException();start=Math.max(0,size-suffix);}else{start=Long.parseLong(pair[0]);if(!pair[1].isEmpty())end=Math.min(end,Long.parseLong(pair[1]));}if(start<0||start>=size||end<start)throw new IllegalArgumentException();}catch(Exception e){return ResponseEntity.status(416).header("Content-Range","bytes */"+size).build();}}
        long from=start,to=end;StreamingResponseBody body=out->{try{vault.range(manifest,from,to,out,()->admin.assetAccess(operator,asset));}catch(Exception e){throw new java.io.IOException("Private media stream unavailable",e);}};
        var response=ResponseEntity.status(range==null?200:206).contentType(MediaType.parseMediaType(row.get("mime").toString())).contentLength(end-start+1).header("Accept-Ranges","bytes").header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff");
        if(range!=null)response.header("Content-Range","bytes "+start+"-"+end+"/"+size);
        return response.body("HEAD".equals(r.getMethod())?null:body);
    }
}
