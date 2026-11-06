package com.yyx.ciyuantown.business.shop;

import com.alipay.api.internal.util.AlipaySignature;
import com.yyx.ciyuantown.business.chat.ChatService;
import com.yyx.ciyuantown.business.membership.MembershipPayments;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest(properties = {
        "ciyuantown.redis.enabled=false",
        "membership.config-key=shop-test-configuration-key-with-more-than-32-characters"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SHOP_TEST_MYSQL", matches = "1")
class ShopIntegrationTests {
    @Autowired JdbcTemplate db;
    @Autowired ShopCatalog catalog;
    @Autowired ShopOrders orders;
    @Autowired ShopPayment payment;
    @Autowired ChatService chat;
    @Autowired MockMvc mvc;
    @MockitoSpyBean MembershipPayments alipay;

    @BeforeEach
    void isolatedSchema() {
        assertTrue(db.queryForObject("SELECT DATABASE()", String.class).startsWith("ciyuantown_shop_verify"),
                "Use a dedicated shop verification database");
    }

    @Test
    void priceUsesOriginalDiscountedValueAndIndependentRounding() {
        var price = ShopCatalog.price(335, 30);
        assertEquals(101, price.amountFen());
        assertEquals(new BigDecimal("10.1"), price.coins());
        assertFalse(price.minimumCoinConfirmation());
        var minimum = ShopCatalog.price(10, 6);
        assertEquals(1, minimum.amountFen());
        assertEquals(new BigDecimal("0.1"), minimum.coins());
        assertTrue(minimum.minimumCoinConfirmation());
    }

    @Test
    void staleQuoteAndMissingMinimumConfirmationCannotDebit() {
        long user = user("3.1");
        String product = product(10, 6);
        db.update("INSERT INTO user_membership(user_id,plan_id,expires_at) VALUES(?,'go',CURRENT_TIMESTAMP+INTERVAL 1 DAY)", user);
        var quote = catalog.detail(user, product);
        assertTrue(quote.price().minimumCoinConfirmation());
        assertThrows(BizException.class, () -> payment.create(user, buy(quote, "COIN", null, "unconfirmed-1", false)));
        assertEquals(new BigDecimal("3.1"), balance(user));
        var done = payment.create(user, buy(quote, "COIN", null, "minimum-confirmed-1", true));
        assertEquals("FULFILLED", done.order().status());
        assertEquals(new BigDecimal("3.0"), balance(user));
        assertEquals(new BigDecimal("-0.1"), db.queryForObject(
                "SELECT amount FROM user_coin_ledger WHERE user_id=?", BigDecimal.class, user));
        assertEquals("shop-" + product, db.queryForObject("SELECT avatar_frame FROM app_user WHERE id=?", String.class, user));
        assertTrue(catalog.detail(user, product).owned());
        db.update("UPDATE shop_product SET enabled=FALSE WHERE id=?", product);
        assertTrue(catalog.detail(user, product).owned());
        assertFalse(catalog.detail(user, product).enabled());
        assertThrows(BizException.class, () -> catalog.shareCard(product));

        long secondUser = user("100.0");
        String other = product(300, 80);
        var before = catalog.detail(secondUser, other);
        db.update("UPDATE shop_product SET price_fen=600,version=version+1 WHERE id=?", other);
        assertThrows(BizException.class, () -> payment.create(secondUser, buy(before, "COIN", null, "stale-quote-1", false)));
        db.update("UPDATE app_user SET coin_balance=0.2 WHERE id=?", secondUser);
        assertThrows(BizException.class, () -> payment.create(secondUser,
                buy(catalog.detail(secondUser, other), "COIN", null, "insufficient-1", false)));
        assertEquals(new BigDecimal("0.2"), balance(secondUser));

        long expiredUser = user("100.0");
        db.update("INSERT INTO user_membership(user_id,plan_id,expires_at) VALUES(?,'go',CURRENT_TIMESTAMP-INTERVAL 1 DAY)", expiredUser);
        assertEquals(100, catalog.detail(expiredUser, other).price().discount());
    }

    @Test
    void concurrentPurchasesGrantAndDebitOnce() throws Exception {
        long user = user("100.1");
        String product = product(300, 80);
        var quote = catalog.detail(user, product);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return payment.create(user, buy(quote,"COIN",null,"same-request-1",false)); });
            var second = pool.submit(() -> { gate.await(); return payment.create(user, buy(quote,"COIN",null,"same-request-1",false)); });
            gate.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS).order().id(), second.get(10, TimeUnit.SECONDS).order().id());
        }
        assertEquals(new BigDecimal("70.1"), balance(user));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM user_coin_ledger WHERE user_id=?", Integer.class, user));
        assertEquals(1, catalog.detail(user, product).soldCount());
        assertThrows(BizException.class, () -> payment.create(user, buy(quote,"COIN",null,"different-req-1",false)));
    }

    @Test
    void realSignedCallbackAndQueryBothFulfillSnapshots() throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keys = keyGen.generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        var app = profile("APP", privateKey, publicKey);
        profile("H5", privateKey, publicKey);
        catalog.saveH5Base("https://shop.example.test");
        long user = user("100.0");
        String product = product(300, 80);
        var checkout = payment.create(user, buy(catalog.detail(user, product), "ALIPAY", "APP", "app-signed-1", false));
        assertTrue(checkout.payment().orderInfo().contains("alipay.trade.app.pay"));
        var order = checkout.order();
        var unsigned = new HashMap<>(Map.of(
                "out_trade_no", order.id(), "trade_no", "trade-" + order.id(), "app_id", app.appId(),
                "seller_id", app.sellerId(), "total_amount", "3.00", "trade_status", "TRADE_SUCCESS"));
        var callback = post("/app/membership/alipay/notify");
        unsigned.forEach(callback::param);
        callback.param("sign_type", "RSA2").param("sign", "invalid");
        mvc.perform(callback).andExpect(content().string("failure"));
        assertEquals("PENDING", orders.order(order.id()).status());

        db.update("UPDATE shop_product SET enabled=FALSE,price_fen=900 WHERE id=?", product);
        var valid = post("/app/membership/alipay/notify");
        unsigned.forEach(valid::param);
        valid.param("sign_type", "RSA2").param("sign", AlipaySignature.rsaSign(
                AlipaySignature.getSignCheckContentV1(unsigned), privateKey, "UTF-8", "RSA2"));
        mvc.perform(valid).andExpect(content().string("success"));
        mvc.perform(valid).andExpect(content().string("success"));
        assertEquals("FULFILLED", orders.order(order.id()).status());
        assertEquals(1, catalog.detail(user, product).soldCount());
        assertEquals("shop-" + product, db.queryForObject("SELECT avatar_frame FROM app_user WHERE id=?", String.class, user));
        assertEquals(new BigDecimal("100.0"), balance(user));

        String next = product(600, 80);
        var h5 = payment.create(user, buy(catalog.detail(user, next), "ALIPAY", "H5", "h5-signed-1", false));
        assertTrue(h5.payment().paymentUrl().startsWith("https://openapi-sandbox.dl.alipaydev.com/"));
        assertTrue(java.net.URLDecoder.decode(h5.payment().paymentUrl(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("/#/pages/shop/detail?id=" + next));
        var h5id = h5.order().id();
        db.update("UPDATE shop_order SET next_check_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE id=?", h5id);
        doReturn(new MembershipPayments.Trade("TRADE_SUCCESS", "trade-" + h5id, h5id, 600))
                .when(alipay).query(argThat((MembershipPayments.TradeRequest request) -> request != null && h5id.equals(request.id())));
        payment.reconcile(h5id);
        assertEquals("FULFILLED", orders.order(h5id).status());

        String unpaid = product(300, 80);
        var cancel = payment.create(user, buy(catalog.detail(user, unpaid), "ALIPAY", "APP", "app-cancel-1", false)).order();
        db.update("UPDATE shop_order SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 MINUTE,next_check_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE id=?", cancel.id());
        doReturn(new MembershipPayments.Trade("TRADE_CLOSED", "", cancel.id(), 0))
                .when(alipay).query(argThat((MembershipPayments.TradeRequest request) -> request != null && cancel.id().equals(request.id())));
        payment.reconcile(cancel.id());
        assertEquals("CLOSED", orders.order(cancel.id()).status());
        assertFalse(catalog.detail(user, unpaid).owned());
    }

    @Test
    void productChatCardUsesServerSnapshotAndSurvivesDelisting() {
        long sender = user("0.0");
        long recipient = user("0.0");
        String product = product(600, 80);
        var card = catalog.shareCard(product);
        db.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?)", sender, recipient);
        var send = new ChatService.Send(Long.toString(recipient), "shop-card-test-1", "product",
                "伪造的客户端标题", null, null, null, product);
        var message = chat.send(sender, send);
        assertEquals(card, message.product());
        assertEquals("", message.content());
        assertEquals(message.id(), chat.send(sender, send).id());
        db.update("UPDATE shop_product SET enabled=FALSE,price_fen=900 WHERE id=?", product);
        assertEquals(card, chat.message(recipient, Long.parseLong(message.id())).product());
        assertThrows(BizException.class, () -> chat.send(sender, new ChatService.Send(Long.toString(recipient),
                "shop-card-test-2", "product", "", null, null, null, product)));
    }

    private MembershipPayments.Profile profile(String channel, String privateKey, String publicKey) {
        db.update("DELETE FROM membership_payment_active WHERE channel=?", channel);
        return alipay.save(new MembershipPayments.Profile("",channel,"SANDBOX","2026000000000001",
                "2088000000000001","PUBLIC_KEY",privateKey,publicKey,"","","",
                "https://shop.example.test/app/membership/alipay/notify", channel.equals("H5") ? "https://shop.example.test/" : ""));
    }

    private long user(String balance) {
        String qq = "82" + java.util.concurrent.ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
        return new SimpleJdbcInsert(db).withTableName("app_user").usingGeneratedKeyColumns("id")
                .usingColumns("qq_number","email","nickname","coin_balance")
                .executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname","商铺测试",
                        "coin_balance",new BigDecimal(balance))).longValue();
    }

    private String product(long fen, int discount) {
        String name = "测试" + UUID.randomUUID();
        catalog.save(new ShopCatalog.Product("",0,name,"测试商品",List.of("/uploads/shop/moon-orbit-cover.webp"),
                "/uploads/shop/moon-orbit-frame.png",fen,Map.of("go",discount),100,true,0));
        return catalog.catalog(name,1).getFirst().id();
    }

    private ShopOrders.Create buy(ShopCatalog.Detail quote,String method,String channel,String key,boolean confirmed) {
        return new ShopOrders.Create(quote.id(),quote.version(),method,channel,key,quote.price(),confirmed);
    }

    private BigDecimal balance(long user) {
        return db.queryForObject("SELECT coin_balance FROM app_user WHERE id=?", BigDecimal.class, user);
    }
}
