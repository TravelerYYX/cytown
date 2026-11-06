package com.yyx.ciyuantown.business.shop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.level.LevelConfig;
import com.yyx.ciyuantown.business.membership.MembershipStore;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.ResourceCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ShopCatalog {
    private static final BigDecimal MIN_COINS = new BigDecimal("0.1");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final MembershipStore memberships;
    private final ResourceCleanupService cleanup;

    public record Product(String id, long version, String name, String description, List<String> images,
                          String frameUrl, long priceFen, Map<String, Integer> discounts,
                          int sortOrder, boolean enabled, long soldCount) {}
    public record Price(long amountFen, BigDecimal coins, int discount, boolean minimumCoinConfirmation) {}
    public record Card(String id, String name, String coverUrl, long priceFen, long soldCount) {}
    public record Detail(String id, long version, String name, String description, List<String> images,
                         long priceFen, boolean enabled, Price price, boolean owned, long soldCount) {}
    public record Tier(String id, String name) {}

    public List<Card> catalog(String query, int page) {
        if (page < 1 || page > 100000) throw new BizException("分页参数无效");
        String search = query == null ? "" : query.strip();
        if (search.length() > 80) throw new BizException("搜索内容过长");
        return products("WHERE enabled=TRUE AND LOCATE(?,name)>0 ORDER BY sort_order,id LIMIT 21 OFFSET ?",
                search, (page - 1) * 20).stream().map(ShopCatalog::card).toList();
    }

    public List<Product> adminProducts() {
        return products("ORDER BY sort_order,id");
    }

    public List<Tier> tiers() {
        return memberships.config().plans().stream().filter(p -> !p.deleted())
                .map(p -> new Tier(p.id(), p.name())).toList();
    }

    public Detail detail(Long user, String id) {
        Product p = product(id, false);
        return new Detail(p.id(), p.version(), p.name(), p.description(), p.images(), p.priceFen(), p.enabled(),
                price(p, user), user != null && owned(user, id), p.soldCount());
    }

    public Card shareCard(String id) {
        var p = product(id, false);
        if (!p.enabled()) throw new BizException("商品已下架");
        return card(p);
    }

    private static Card card(Product p) {
        return new Card(p.id(), p.name(), p.images().getFirst(), p.priceFen(), p.soldCount());
    }

    Product product(String id, boolean lock) {
        return products(lock ? "WHERE id=? FOR UPDATE" : "WHERE id=?", numericId(id)).stream().findFirst()
                .orElseThrow(() -> new BizException("商品不存在"));
    }

    boolean owned(Long user, String id) {
        return db.queryForObject("SELECT COUNT(*) FROM user_avatar_frame WHERE user_id=? AND frame_id=?",
                Integer.class, user, "shop-" + id) > 0;
    }

    Price price(Product p, Long user) {
        if (user == null) return price(p.priceFen(), 100);
        var member = memberships.member(user);
        return price(p.priceFen(), member.active() ? p.discounts().getOrDefault(member.planId(), 100) : 100);
    }

    static Price price(long fen, int discount) {
        var discountedFen = BigDecimal.valueOf(fen).multiply(BigDecimal.valueOf(discount, 2));
        var rawCoins = discountedFen.movePointLeft(1);
        return new Price(discountedFen.setScale(0, RoundingMode.HALF_UP).longValueExact(),
                rawCoins.setScale(1, RoundingMode.HALF_UP).max(MIN_COINS),
                discount, rawCoins.compareTo(MIN_COINS) < 0);
    }

    @Transactional
    public void save(Product input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().strip().length() > 80
                || input.description() == null || input.description().length() > 500
                || input.images() == null || input.images().isEmpty() || input.images().size() > 10
                || input.priceFen() < 10 || input.priceFen() > 100_000_000 || input.discounts() == null
                || input.discounts().size() > 100) throw new BizException("商品配置无效，标价最低 ¥0.10");
        input.images().forEach(ShopCatalog::image);
        image(input.frameUrl());
        input.discounts().forEach((id, discount) -> {
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,27}") || discount == null
                    || discount < 1 || discount > 100 || price(input.priceFen(), discount).amountFen() < 1)
                throw new BizException("会员折扣须为 1% 至 100%，人民币实付至少一分");
        });
        Long id = input.id() == null || input.id().isEmpty() ? null : numericId(input.id());
        cleanup.track("shop_product", id);
        var name = input.name().strip();
        var images = encode(input.images());
        var discounts = encode(input.discounts());
        if (id == null) {
            db.update("""
                    INSERT INTO shop_product(sku,name,description,images_json,frame_url,price_fen,discounts_json,sort_order,enabled)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), name, input.description(), images, input.frameUrl(),
                    input.priceFen(), discounts, input.sortOrder(), input.enabled());
        } else if (db.update("""
                UPDATE shop_product SET name=?,description=?,images_json=?,frame_url=?,price_fen=?,discounts_json=?,
                    sort_order=?,enabled=?,version=version+1 WHERE id=? AND version=?
                """, name, input.description(), images, input.frameUrl(), input.priceFen(), discounts,
                input.sortOrder(), input.enabled(), id, input.version()) != 1) {
            throw new BizException("商品配置已变化，请刷新后编辑");
        }
    }

    public List<Map<String, Object>> frames() {
        return db.query("SELECT id,name,frame_url FROM shop_product ORDER BY id",
                (rs, row) -> Map.of("id", "shop-" + rs.getString("id"), "name", rs.getString("name"),
                        "image", Map.of("url", rs.getString("frame_url"), "originalBytes", 0, "processedBytes", 0)));
    }

    public String h5Base() {
        return db.queryForList("SELECT config_value FROM system_config WHERE config_key='shop_h5_base_url'",
                String.class).stream().findFirst().orElse("");
    }

    public String saveH5Base(String value) {
        if (value == null) throw new BizException("请输入有效的 H5 基础地址");
        String base = value.strip().replaceAll("/+$", "");
        if (!base.isEmpty()) {
            try {
                var uri = URI.create(base);
                String host = uri.getHost();
                boolean local = host != null && (Set.of("localhost", "127.0.0.1", "[::1]").contains(host)
                        || host.matches("(?:10\\.|192\\.168\\.|172\\.(?:1[6-9]|2[0-9]|3[01])\\.)[0-9.]+"));
                if (base.length() > 500 || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getFragment() != null || uri.getQuery() != null
                        || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme())))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new BizException("请输入 HTTPS H5 基础地址，本地和内网联调可使用 HTTP");
            }
        }
        db.update("""
                INSERT INTO system_config(config_key,config_value,remark) VALUES('shop_h5_base_url',?,'商铺 H5 站点基础地址')
                ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)
                """, base);
        return base;
    }

    public String h5Product(String id) {
        numericId(id);
        String base = h5Base();
        if (base.isBlank()) throw new BizException("商铺 H5 地址未配置");
        return base + "/#/pages/shop/detail?id=" + id;
    }

    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new BizException("商品数据格式无效"); }
    }

    private List<Product> products(String where, Object... args) {
        return db.query("SELECT * FROM shop_product " + where, (rs, row) -> new Product(
                rs.getString("id"), rs.getLong("version"), rs.getString("name"), rs.getString("description"),
                decode(rs.getString("images_json"), new TypeReference<List<String>>() {}),
                rs.getString("frame_url"), rs.getLong("price_fen"),
                decode(rs.getString("discounts_json"), new TypeReference<Map<String, Integer>>() {}),
                rs.getInt("sort_order"), rs.getBoolean("enabled"), rs.getLong("sold_count")), args);
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception ex) { throw new BizException("商品数据读取失败"); }
    }

    private static void image(String url) {
        if (url == null || url.isBlank()) throw new BizException("请配置商品及头像框图片");
        LevelConfig.validateBadge(url);
    }

    static long numericId(String id) {
        try {
            if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw new BizException("商品标识无效");
        }
    }
}
