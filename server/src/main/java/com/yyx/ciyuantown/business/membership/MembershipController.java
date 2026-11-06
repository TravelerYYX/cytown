package com.yyx.ciyuantown.business.membership;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import com.yyx.ciyuantown.common.util.JwtUtil;
import com.yyx.ciyuantown.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
public class MembershipController {
    private final MembershipStore store;
    private final MembershipPayments payments;
    private final MembershipService service;
    private final JwtUtil jwt;
    private final com.yyx.ciyuantown.business.shop.ShopPayment shopPayment;
    private Long user(HttpServletRequest r){return (Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID);}
    private void admin(HttpServletRequest r){String token=r.getHeader("Authorization");if(token==null||jwt.userType(token.substring(7))!=0)throw new BizException("仅管理员可管理会员与支付");}
    @GetMapping("/app/membership/plans") public Result<MembershipConfig> plans(){var c=store.config();return Result.success(new MembershipConfig(c.version(),c.selling(),c.notice(),c.contact(),c.agreement(),c.plans().stream().filter(p->!p.archived()).toList()));}
    @AppLoginRequired @GetMapping("/app/membership/me") public Result<MembershipStore.Member> me(HttpServletRequest r){return Result.success(store.member(user(r)));}
    @GetMapping("/app/membership/summaries") public Result<Map<String,MembershipStore.Member>> summaries(@RequestParam List<Long> userIds){return Result.success(store.summaries(userIds));}
    @AppLoginRequired @PostMapping("/app/membership/orders") public Result<MembershipPayments.PayData> create(HttpServletRequest r,@RequestBody MembershipStore.Create input){return Result.success(service.create(user(r),input));}
    @AppLoginRequired @PostMapping("/app/membership/orders/{id}/pay") public Result<MembershipPayments.PayData> pay(HttpServletRequest r,@PathVariable String id){return Result.success(service.pay(user(r),id));}
    @AppLoginRequired @GetMapping("/app/membership/orders") public Result<Map<String,Object>> orders(HttpServletRequest r,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return Result.success(store.page(user(r),pageNum,pageSize,null,null));}
    @AppLoginRequired @PostMapping("/app/membership/orders/{id}/refresh") public Result<MembershipStore.Order> refresh(HttpServletRequest r,@PathVariable String id){store.owned(user(r),id);service.reconcile(id);return Result.success(store.owned(user(r),id));}
    @AppLoginRequired @GetMapping("/app/membership/orders/{id}") public Result<MembershipStore.Order> order(HttpServletRequest r,@PathVariable String id){return Result.success(store.owned(user(r),id));}
    @GetMapping("/membership/config") public Result<MembershipConfig> config(HttpServletRequest r){admin(r);return Result.success(store.config());}
    @PostMapping("/membership/config") public Result<MembershipConfig> config(HttpServletRequest r,@RequestBody MembershipConfig c){admin(r);if(c.selling()){payments.active("APP");payments.active("H5");}return Result.success(store.save(c));}
    @GetMapping("/membership/payments") public Result<List<MembershipPayments.Profile>> payments(HttpServletRequest r){admin(r);return Result.success(payments.list());}
    @PostMapping("/membership/payments") public Result<MembershipPayments.Profile> payments(HttpServletRequest r,@RequestBody MembershipPayments.Profile p){admin(r);return Result.success(payments.save(p));}
    @GetMapping("/membership/orders") public Result<Map<String,Object>> orders(HttpServletRequest r,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String status,@RequestParam(required=false)String search){admin(r);return Result.success(store.page(null,pageNum,pageSize,status,search));}
    @PostMapping("/membership/orders/{id}/refresh") public Result<MembershipStore.Order> check(HttpServletRequest r,@PathVariable String id){admin(r);service.reconcile(id);return Result.success(store.order(id));}
    @PostMapping(value="/app/membership/alipay/notify",produces="text/plain;charset=UTF-8") public String notify(HttpServletRequest r){try{
        var params=new HashMap<String,String>();for(var entry:r.getParameterMap().entrySet()){if(entry.getValue().length!=1)return "failure";params.put(entry.getKey(),entry.getValue()[0]);}if (shopPayment.accepts(params.get("out_trade_no"))) shopPayment.notify(params); else service.notify(params);return "success";
    }catch(Exception e){return "failure";}}
    @GetMapping("/app/membership/frames") public Result<List<Map<String,Object>>> frames(){return Result.success(store.frames());}
    @AppLoginRequired @GetMapping("/app/membership/frames/owned") public Result<List<Map<String,Object>>> frames(HttpServletRequest r){return Result.success(store.ownedFrames(user(r)));}
}
