package com.yyx.ciyuantown.business.checkin;

import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class CoinController {
    private final CoinService service;

    private Long user(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    @GetMapping("/checkin/coin-punch-config")
    public Result<CoinService.Rewards> config() {
        return Result.success(service.config());
    }

    @PostMapping("/checkin/coin-punch-config")
    public Result<CoinService.Rewards> save(@RequestBody JsonNode rewards) {
        return Result.success(service.save(new CoinService.Rewards(
                rewardAmount(rewards.path("points")), rewardAmount(rewards.path("coins")))));
    }

    private long rewardAmount(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new BizException("积分和次元币须为 0 至 1000000 的整数");
        }
        return value.longValue();
    }

    @AppLoginRequired
    @GetMapping("/app/coin/me")
    public Result<CoinService.State> state(HttpServletRequest request) {
        return Result.success(service.state(user(request)));
    }

    @AppLoginRequired
    @PostMapping("/app/coin/punch")
    public Result<CoinService.State> claim(HttpServletRequest request) {
        return Result.success(service.claim(user(request)));
    }

    @AppLoginRequired
    @GetMapping("/app/coin/ledger")
    public Result<CoinService.LedgerPage> ledger(HttpServletRequest request,
            @RequestParam(defaultValue = "earned") String direction, @RequestParam(required = false) Long cursor) {
        return Result.success(service.ledger(user(request), direction, cursor));
    }
}
