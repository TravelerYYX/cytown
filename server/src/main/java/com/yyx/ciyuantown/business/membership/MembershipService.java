package com.yyx.ciyuantown.business.membership;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import com.yyx.ciyuantown.common.exception.BizException;
import java.util.*;
import java.math.BigDecimal;

@Service @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
public class MembershipService {
    private final MembershipStore store;
    private final MembershipPayments payments;
    public MembershipPayments.PayData create(Long user,MembershipStore.Create input){
        if(input==null||input.channel()==null||!Set.of("APP","H5").contains(input.channel()))throw new BizException("请选择支付渠道");
        for(var o:store.pending(user))if(!o.expiresAt().isAfter(store.now())||"PAID".equals(o.status()))reconcile(o.id());
        var p=payments.active(input.channel());var order=store.create(user,input,p.id());return pay(user,order.id());
    }
    public MembershipPayments.PayData pay(Long user,String id){var o=store.owned(user,id);
        if(!"PENDING".equals(o.status())||!o.expiresAt().isAfter(store.now()))throw new BizException("订单已支付或已过期，请查看订单状态");return payments.pay(o);}
    public void notify(Map<String,String> params){
        String id=params.get("out_trade_no");if(id==null||!id.matches("M[a-f0-9]{32}"))throw new BizException("通知订单无效");
        var o=store.order(id);var p=payments.get(o.profileId());
        if(!payments.verify(p,params)||!p.appId().equals(params.get("app_id"))||!p.sellerId().equals(params.get("seller_id")))throw new BizException("支付通知校验失败");
        long amount;try{amount=new BigDecimal(params.get("total_amount")).movePointRight(2).longValueExact();}catch(Exception e){throw new BizException("金额无效");}
        if(amount!=o.amountFen())throw new BizException("金额不符");
        String status=params.get("trade_status");
        if(Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(status)){store.paid(o.id(),params.get("trade_no"),amount);fulfill(o.id());}
        else if("TRADE_CLOSED".equals(status))store.closed(o.id());
    }
    private void fulfill(String id){try{store.fulfill(id);}catch(Exception e){log.warn("Membership fulfillment pending: order={}, failureType={}",id,e.getClass().getSimpleName());store.retry(id,e instanceof BizException?e.getMessage():"付款已确认，权益发放等待重试或人工处理");}}
    public void reconcile(String id){var o=store.order(id);if("ACTIVE".equals(o.status())||"CLOSED".equals(o.status()))return;
        if("PAID".equals(o.status())){fulfill(id);return;}
        if(!store.claimCheck(id))return;
        try{var trade=payments.query(o);
            if(!o.id().equals(trade.orderId()))throw new BizException("支付宝查询订单不匹配");
            if(Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(trade.status())){store.paid(id,trade.tradeNo(),trade.amountFen());fulfill(id);}
            else if("TRADE_CLOSED".equals(trade.status()))store.closed(id);
            else if(!o.expiresAt().isAfter(store.now())&&payments.close(o))store.closed(id);
            else store.retry(id,"");
        }catch(Exception e){store.retry(id,"支付宝状态待确认，系统将自动重试");}
    }
    @Scheduled(fixedDelay=60000,initialDelay=30000) public void retry(){try{for(var o:store.due())reconcile(o.id());}catch(org.springframework.dao.DataAccessException ignored){/* migration may not yet be installed */}}
}
