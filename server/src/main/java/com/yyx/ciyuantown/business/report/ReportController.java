package com.yyx.ciyuantown.business.report;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.api.ResultCode;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class ReportController {
    private final ReportService reports;
    private final UserBlockService blocks;
    private long user(HttpServletRequest request) {
        Long id=(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if(id==null)throw new BizException(ResultCode.UNAUTHORIZED);return id;
    }
    public record Block(Boolean blocked) {}
    public record Id(Long id) {}
    @com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired
    @PostMapping("/app/user/{id}/block") public Result<Boolean> block(HttpServletRequest r,@PathVariable long id,@RequestBody Block p) {
        if(p==null||p.blocked()==null||id<=0)throw new BizException("拉黑参数无效");return Result.success(blocks.set(user(r),id,p.blocked()));
    }
    @com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired
    @GetMapping("/app/report/options") public Result<ReportService.Options> options(HttpServletRequest r){user(r);return Result.success(reports.options());}
    @com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired
    @PostMapping("/app/report/submit") public Result<String> submit(HttpServletRequest r,@RequestBody ReportService.Submit p){return Result.success(reports.submit(user(r),p));}
    @GetMapping("/report/options") public Result<ReportService.Options> adminOptions(){return Result.success(reports.options());}
    @PostMapping("/report/option/create") public Result<ReportService.Option> create(@RequestBody ReportService.OptionParam p){return Result.success(reports.saveOption(p,true));}
    @PostMapping("/report/option/update") public Result<ReportService.Option> update(@RequestBody ReportService.OptionParam p){return Result.success(reports.saveOption(p,false));}
    @PostMapping("/report/option/delete") public Result<Void> delete(@RequestBody Id p){reports.deleteOption(p.id());return Result.success();}
    @GetMapping("/report/list") public Result<Page<ReportService.Report>> list(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return Result.success(reports.list(pageNum,pageSize));}
    @GetMapping("/report/{id}") public Result<ReportService.Detail> detail(@PathVariable long id){return Result.success(reports.detail(id));}
}
