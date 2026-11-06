package com.yyx.ciyuantown.business.shop;

import com.yyx.ciyuantown.business.appuser.service.AppUserAccessService;
import com.yyx.ciyuantown.business.membership.MembershipPayments;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.ResourceCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ShopOrders {
    private final JdbcTemplate db;
    private final Clock clock;
    private final ShopCatalog catalog;
    private final AppUserAccessService users;
    private final ResourceCleanupService cleanup;

    public record Create(String productId, long version, String method, String channel, String requestKey,
                         ShopCatalog.Price price, boolean minimumConfirmed) {}
    public record Order(String id, String userId, String productId, String productName, String method,
                        long amountFen, BigDecimal coins, String profileId, String channel, String status,
                        String tradeNo, String errorMessage, LocalDateTime createdAt, LocalDateTime expiresAt) {
        MembershipPayments.TradeRequest tradeRequest(String returnUrl) {
            return new MembershipPayments.TradeRequest(id, profileId, channel, amountFen, productName, expiresAt, returnUrl);
        }
    }

    @Transactional
    @CacheEvict(cacheNames = {"post-list", "post-detail"}, allEntries = true)
    public Order create(Long user, Create input, String profileId) {
        cleanup.lock();
        var account = users.requireForUpdate(user);
        var existing = query("WHERE user_id=? AND request_key=?", user, input.requestKey());
        if (!existing.isEmpty()) {
            var order = existing.getFirst();
            if (!order.productId().equals(input.productId()) || !order.method().equals(input.method())
                    || !Objects.equals(order.channel(), input.channel())) throw new BizException("购买请求标识已使用");
            return order;
        }
        var product = catalog.product(input.productId(), true);
        if (catalog.owned(user, product.id())) throw new BizException("你已拥有这款头像框");
        if (pending(user, product.id()) != null) throw new BizException("该商品有待确认订单，请先查看订单状态");
        if (!product.enabled()) throw new BizException("商品已下架");
        var price = catalog.price(product, user);
        if (input.version() != product.version() || !samePrice(price, input.price()))
            throw new BizException("价格或会员优惠已变化，请刷新后重新确认");

        boolean coins = "COIN".equals(input.method());
        if (coins && price.minimumCoinConfirmation() && !input.minimumConfirmed())
            throw new BizException("请确认按最低 0.1 次元币付款");
        var balance = account.getCoinBalance() == null ? BigDecimal.ZERO : account.getCoinBalance();
        if (coins && balance.compareTo(price.coins()) < 0) throw new BizException("次元币余额不足");
        String id = "S" + UUID.randomUUID().toString().replace("-", "");
        var now = now();
        db.update("""
                INSERT INTO shop_order(id,user_id,product_id,request_key,product_json,method,amount_fen,amount_coins,
                    profile_id,channel,status,created_at,expires_at,next_check_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, user, ShopCatalog.numericId(product.id()), input.requestKey(), catalog.encode(product),
                input.method(), coins ? 0L : price.amountFen(), coins ? price.coins() : BigDecimal.ZERO, profileId,
                input.channel(), coins ? "PAID" : "PENDING", now, now.plusMinutes(30), now.plusSeconds(30));
        if (coins) {
            var remaining = balance.subtract(price.coins());
            db.update("UPDATE app_user SET coin_balance=? WHERE id=?", remaining, user);
            db.update("INSERT INTO user_coin_ledger(user_id,event_key,amount,balance_after) VALUES(?,?,?,?)",
                    user, "shop:" + id, price.coins().negate(), remaining);
            grant(user, order(id));
        }
        return order(id);
    }

    private static boolean samePrice(ShopCatalog.Price actual, ShopCatalog.Price input) {
        return input != null && input.coins() != null && actual.amountFen() == input.amountFen()
                && actual.coins().compareTo(input.coins()) == 0 && actual.discount() == input.discount()
                && actual.minimumCoinConfirmation() == input.minimumCoinConfirmation();
    }

    public Order pending(Long user, String product) {
        return query("WHERE user_id=? AND product_id=? AND status IN ('PENDING','PAID') ORDER BY created_at DESC LIMIT 1",
                user, ShopCatalog.numericId(product)).stream().findFirst().orElse(null);
    }

    public Order order(String id) {
        if (id == null || !id.matches("S[a-f0-9]{32}")) throw new BizException("订单无效");
        return query("WHERE id=?", id).stream().findFirst().orElseThrow(() -> new BizException("订单不存在"));
    }

    public Order owned(Long user, String id) {
        var order = order(id);
        if (!order.userId().equals(user.toString())) throw new BizException("无权访问该订单");
        return order;
    }

    @Transactional
    public void paid(String id, String trade, long fen) {
        var order = locked(id);
        if (!"ALIPAY".equals(order.method()) || fen != order.amountFen() || trade == null || trade.isBlank()
                || trade.length() > 80 || order.tradeNo() != null && !order.tradeNo().equals(trade))
            throw new BizException("支付金额或交易号不匹配");
        if ("CLOSED".equals(order.status())) throw new BizException("已关闭订单出现付款，须人工核对");
        db.update("UPDATE shop_order SET status='PAID',trade_no=?,next_check_at=? WHERE id=? AND status='PENDING'",
                trade, now(), id);
    }

    @Transactional
    @CacheEvict(cacheNames = {"post-list", "post-detail"}, allEntries = true)
    public void fulfill(String id) {
        var order = locked(id);
        if (!"PAID".equals(order.status())) return;
        long user = Long.parseLong(order.userId());
        users.requireForUpdate(user);
        grant(user, order);
    }

    private void grant(Long user, Order order) {
        var frame = "shop-" + order.productId();
        db.update("""
                INSERT INTO user_avatar_frame(user_id,frame_id,expires_at) VALUES(?,?,NULL)
                ON DUPLICATE KEY UPDATE expires_at=NULL
                """, user, frame);
        db.update("UPDATE app_user SET avatar_frame=?,avatar_frame_expires_at=NULL WHERE id=?", frame, user);
        db.update("UPDATE shop_product SET sold_count=sold_count+1 WHERE id=?", ShopCatalog.numericId(order.productId()));
        db.update("UPDATE shop_order SET status='FULFILLED',error_message='' WHERE id=?", order.id());
    }

    @Transactional
    public void closed(String id) {
        locked(id);
        db.update("UPDATE shop_order SET status='CLOSED',error_message='' WHERE id=? AND status='PENDING'", id);
    }

    private Order locked(String id) {
        var initial = order(id);
        db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE", Long.parseLong(initial.userId()));
        return query("WHERE id=? FOR UPDATE", id).getFirst();
    }

    public boolean claimCheck(String id) {
        return db.update("UPDATE shop_order SET next_check_at=? WHERE id=? AND status='PENDING' AND next_check_at<=?",
                now().plusSeconds(15), id, now()) == 1;
    }

    public void retry(String id, String message) {
        db.update("UPDATE shop_order SET next_check_at=?,error_message=? WHERE id=? AND status IN ('PENDING','PAID')",
                now().plusMinutes(1), message, id);
    }

    public List<Order> due() {
        return query("WHERE status IN ('PENDING','PAID') AND next_check_at<=? ORDER BY next_check_at LIMIT 30", now());
    }

    public Map<String, Object> page(int page, String search) {
        if (page < 1 || page > 100000) throw new BizException("分页参数无效");
        if (search == null || search.isBlank()) {
            return Map.of("records", query("ORDER BY created_at DESC,id LIMIT 20 OFFSET ?", (page - 1) * 20),
                    "total", db.queryForObject("SELECT COUNT(*) FROM shop_order", Long.class));
        }
        String filter = " WHERE id=? OR trade_no=? OR CAST(user_id AS CHAR)=?";
        return Map.of("records", query(filter + " ORDER BY created_at DESC,id LIMIT 20 OFFSET ?",
                        search, search, search, (page - 1) * 20),
                "total", db.queryForObject("SELECT COUNT(*) FROM shop_order" + filter, Long.class, search, search, search));
    }

    private List<Order> query(String clause, Object... args) {
        return db.query("""
                SELECT id,user_id,product_id,JSON_UNQUOTE(JSON_EXTRACT(product_json,'$.name')) AS product_name,
                    method,amount_fen,amount_coins,profile_id,channel,status,trade_no,error_message,created_at,expires_at
                FROM shop_order
                """ + " " + clause, (rs, row) -> new Order(rs.getString("id"), rs.getString("user_id"),
                rs.getString("product_id"), rs.getString("product_name"), rs.getString("method"), rs.getLong("amount_fen"),
                rs.getBigDecimal("amount_coins"), rs.getString("profile_id"), rs.getString("channel"), rs.getString("status"),
                rs.getString("trade_no"), rs.getString("error_message"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class)), args);
    }

    LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
