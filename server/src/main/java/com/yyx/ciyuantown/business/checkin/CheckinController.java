package com.yyx.ciyuantown.business.checkin;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import com.yyx.ciyuantown.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController @RequiredArgsConstructor
public class CheckinController {
    private final CheckinService service;
    private final java.time.Clock clock;
    private Long id(HttpServletRequest r){return (Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID);}
    @GetMapping("/checkin/config") public Result<CheckinConfig> config(){return Result.success(service.config());}
    @PostMapping("/checkin/config") public Result<CheckinConfig> save(@RequestBody CheckinConfig c){return Result.success(service.save(c));}
    @GetMapping("/app/checkin/config") public Result<CheckinConfig> publicConfig(){return Result.success(service.config());}
    @AppLoginRequired @GetMapping("/app/checkin/me") public Result<CheckinService.State> me(HttpServletRequest r){return Result.success(service.state(id(r)));}
    @AppLoginRequired @PostMapping("/app/checkin/sign") public Result<CheckinService.Claim> sign(HttpServletRequest r){return Result.success(service.claim(id(r),null));}
    public record Makeup(String date,Long version){}
    @AppLoginRequired @PostMapping("/app/checkin/makeup") public Result<CheckinService.Claim> makeup(HttpServletRequest r,@RequestBody Makeup p){
        LocalDate date;try{date=LocalDate.parse(p.date());}catch(Exception e){throw new BizException("请选择有效日期");}
        if(p.version()==null)throw new BizException("请刷新签到页面后补签");
        if(!date.isBefore(LocalDate.now(clock)))throw new BizException("请选择过去未签到的日期");
        return Result.success(service.claim(id(r),date,p.version()));
    }
    @AppLoginRequired @GetMapping("/app/checkin/history") public Result<Map<String,Object>> history(HttpServletRequest r,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return Result.success(service.history(id(r),pageNum,pageSize));}
    @GetMapping("/app/checkin/frames") public Result<List<CheckinConfig.Frame>> frames(){return Result.success(service.config().frames());}
    @AppLoginRequired @GetMapping("/app/checkin/frames/owned") public Result<List<Map<String,Object>>> owned(HttpServletRequest r){return Result.success(service.owned(id(r)));}
    @GetMapping("/app/checkin/recommendations") public Result<List<Map<String,Object>>> recommendations(){return Result.success(service.recommendations());}
}
