package com.yyx.ciyuantown.business.shop;

import com.yyx.ciyuantown.business.membership.MembershipPayments;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShopPayment {
    private static final Set<String> SETTLED = Set.of("TRADE_SUCCESS", "TRADE_FINISHED");
    private final ShopOrders orders;
    private final ShopCatalog catalog;
    private final MembershipPayments payments;

    public record Checkout(ShopOrders.Order order, MembershipPayments.PayData payment) {}

    public boolean accepts(String orderId) {
        return orderId != null && orderId.matches("S[a-f0-9]{32}");
    }

    public boolean available(String channel) {
        if (!validChannel(channel) || "H5".equals(channel) && catalog.h5Base().isBlank()) return false;
        try {
            payments.active(channel);
            return true;
        } catch (BizException ex) {
            return false;
        }
    }

    public Checkout create(Long user, ShopOrders.Create input) {
        if (input == null || input.requestKey() == null || !input.requestKey().matches("[A-Za-z0-9-]{8,80}"))
            throw new BizException("购买请求无效");
        String profileId = null;
        if ("ALIPAY".equals(input.method()) && validChannel(input.channel())) {
            if ("H5".equals(input.channel())) catalog.h5Product(input.productId());
            profileId = payments.active(input.channel()).id();
        } else if (!"COIN".equals(input.method()) || input.channel() != null) {
            throw new BizException("请选择有效的支付方式");
        }
        var order = orders.create(user, input, profileId);
        return new Checkout(order, "PENDING".equals(order.status()) && order.expiresAt().isAfter(orders.now())
                ? launch(order) : null);
    }

    public Checkout pay(Long user, String id) {
        var order = orders.owned(user, id);
        if (!"PENDING".equals(order.status()) || !order.expiresAt().isAfter(orders.now()))
            throw new BizException("订单已支付或过期，请查看订单状态");
        return new Checkout(order, launch(order));
    }

    private MembershipPayments.PayData launch(ShopOrders.Order order) {
        String returnUrl = "H5".equals(order.channel()) ? catalog.h5Product(order.productId()) : null;
        return payments.pay(order.tradeRequest(returnUrl));
    }

    private static boolean validChannel(String channel) {
        return "APP".equals(channel) || "H5".equals(channel);
    }

    public void notify(Map<String, String> params) {
        var order = orders.order(params.get("out_trade_no"));
        if (!"ALIPAY".equals(order.method())) throw new BizException("支付订单无效");
        var profile = payments.get(order.profileId());
        if (!payments.verify(profile, params) || !profile.appId().equals(params.get("app_id"))
                || !profile.sellerId().equals(params.get("seller_id")))
            throw new BizException("支付通知校验失败");
        long amount;
        try {
            amount = new BigDecimal(params.get("total_amount")).movePointRight(2).longValueExact();
        } catch (RuntimeException ex) {
            throw new BizException("支付金额无效");
        }
        if (amount != order.amountFen()) throw new BizException("支付金额不符");
        String status = params.getOrDefault("trade_status", "");
        if (SETTLED.contains(status)) {
            orders.paid(order.id(), params.get("trade_no"), amount);
            fulfill(order.id());
        } else if ("TRADE_CLOSED".equals(status)) {
            orders.closed(order.id());
        }
    }

    public void reconcile(String id) {
        var order = orders.order(id);
        if ("PAID".equals(order.status())) {
            fulfill(id);
            return;
        }
        if (!"PENDING".equals(order.status()) || !orders.claimCheck(id)) return;
        try {
            var request = order.tradeRequest(null);
            var trade = payments.query(request);
            if (!order.id().equals(trade.orderId())) throw new BizException("支付宝查询订单不匹配");
            if (SETTLED.contains(trade.status())) {
                orders.paid(id, trade.tradeNo(), trade.amountFen());
                fulfill(id);
            } else if ("TRADE_CLOSED".equals(trade.status())
                    || !order.expiresAt().isAfter(orders.now()) && payments.close(request)) {
                orders.closed(id);
            }
        } catch (RuntimeException ex) {
            orders.retry(id, "支付宝交易待确认，系统将自动重试");
            log.warn("Shop payment reconciliation deferred for {}", id, ex);
        }
    }

    private void fulfill(String id) {
        try {
            orders.fulfill(id);
        } catch (RuntimeException ex) {
            orders.retry(id, "付款已确认，头像框发放等待重试或人工处理");
            log.warn("Shop fulfillment deferred for {}", id, ex);
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void retry() {
        try {
            for (var order : orders.due()) reconcile(order.id());
        } catch (DataAccessException ex) {
            log.debug("Shop order queue temporarily unavailable", ex);
        }
    }
}
