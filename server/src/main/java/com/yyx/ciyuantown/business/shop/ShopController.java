package com.yyx.ciyuantown.business.shop;

import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import com.yyx.ciyuantown.common.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ShopController {
    private final ShopCatalog catalog;
    private final ShopOrders orders;
    private final ShopPayment payment;
    private final JwtUtil jwt;

    public record Page(List<ShopCatalog.Card> records, boolean more) {}
    public record ProductView(ShopCatalog.Detail product, ShopOrders.Order pending, boolean alipayAvailable) {}
    public record Settings(String h5BaseUrl) {}

    @GetMapping("/app/shop/products")
    public Result<Page> products(@RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "1") int page) {
        var rows = catalog.catalog(q, page);
        return Result.success(new Page(rows.subList(0, Math.min(20, rows.size())), rows.size() > 20));
    }

    @GetMapping("/app/shop/products/{id}")
    public Result<ProductView> product(HttpServletRequest request, @PathVariable String id,
                                       @RequestParam(defaultValue = "APP") String channel) {
        Long user = user(request);
        return Result.success(new ProductView(catalog.detail(user, id), user == null ? null : orders.pending(user, id),
                payment.available(channel)));
    }

    @GetMapping("/app/shop/products/{id}/share")
    public Result<Map<String, String>> share(@PathVariable String id) {
        var card = catalog.shareCard(id);
        String url = catalog.h5Product(id);
        return Result.success(Map.of("url", url, "text", card.name() + " · 次元町商铺 " + url));
    }

    @GetMapping("/app/shop/frames")
    public Result<List<Map<String, Object>>> frames() {
        return Result.success(catalog.frames());
    }

    @AppLoginRequired
    @PostMapping("/app/shop/orders")
    public Result<ShopPayment.Checkout> create(HttpServletRequest request, @RequestBody ShopOrders.Create input) {
        return Result.success(payment.create(user(request), input));
    }

    @AppLoginRequired
    @PostMapping("/app/shop/orders/{id}/pay")
    public Result<ShopPayment.Checkout> pay(HttpServletRequest request, @PathVariable String id) {
        return Result.success(payment.pay(user(request), id));
    }

    @AppLoginRequired
    @PostMapping("/app/shop/orders/{id}/refresh")
    public Result<ShopOrders.Order> refresh(HttpServletRequest request, @PathVariable String id) {
        Long user = user(request);
        orders.owned(user, id);
        payment.reconcile(id);
        return Result.success(orders.owned(user, id));
    }

    @GetMapping("/shop/products")
    public Result<Map<String, Object>> adminProducts(HttpServletRequest request) {
        admin(request);
        return Result.success(Map.of("products", catalog.adminProducts(), "tiers", catalog.tiers()));
    }

    @PostMapping("/shop/products/save")
    public Result<Void> saveProduct(HttpServletRequest request, @RequestBody ShopCatalog.Product input) {
        admin(request);
        catalog.save(input);
        return Result.success();
    }

    @GetMapping("/shop/settings")
    public Result<Settings> settings(HttpServletRequest request) {
        admin(request);
        return Result.success(new Settings(catalog.h5Base()));
    }

    @PostMapping("/shop/settings")
    public Result<Settings> saveSettings(HttpServletRequest request, @RequestBody Settings input) {
        admin(request);
        return Result.success(new Settings(catalog.saveH5Base(input.h5BaseUrl())));
    }

    @GetMapping("/shop/orders")
    public Result<Map<String, Object>> adminOrders(HttpServletRequest request,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(required = false) String search) {
        admin(request);
        return Result.success(orders.page(page, search));
    }

    @PostMapping("/shop/orders/{id}/refresh")
    public Result<ShopOrders.Order> adminRefresh(HttpServletRequest request, @PathVariable String id) {
        admin(request);
        payment.reconcile(id);
        return Result.success(orders.order(id));
    }

    private Long user(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    private void admin(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || jwt.userType(header.substring(7)) != 0)
            throw new BizException("仅管理员可管理商铺");
    }
}
