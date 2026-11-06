package com.yyx.ciyuantown.common.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

/**
 * 官方只读账号写操作拦截：userType=1 的账号仅可浏览（GET/HEAD），其余写请求统一拒绝。
 *
 * <p>按 HTTP 方法判定而非注解标记：管理端写接口语义分散（如 /app-user/list 是 POST 只读查询），
 * 注解白名单维护成本高且易漏；只读账号需要的 POST 查询接口集中在 {@link #READ_ONLY_WHITELIST}。</p>
 *
 * <p>注册顺序必须在 {@link AuthInterceptor} 之后（先鉴权，再判权限）；无 token 时放行交由
 * AuthInterceptor 处理。只读拦截是安全边界，前端隐藏按钮仅是体验优化。</p>
 */
@Component
@RequiredArgsConstructor
public class ReadOnlyInterceptor implements HandlerInterceptor {

    /** 只读账号额外放行的 POST 语义查询接口（管理端全部 POST 查询接口，其余查询均为 GET） */
    private static final Set<String> READ_ONLY_WHITELIST = Set.of(
            "/app-user/list",
            "/post/list"
    );

    private static final String DENY_MESSAGE = "该账号为只读账号，无操作权限";

    private final JwtUtil jwtUtil;

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod())
                || HttpMethod.GET.matches(request.getMethod())
                || HttpMethod.HEAD.matches(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        // 无 token / 非 Bearer：交由 AuthInterceptor 返回 8888
        if (authorization == null || !authorization.startsWith(AuthInterceptor.BEARER_PREFIX)) {
            return true;
        }
        String token = authorization.substring(AuthInterceptor.BEARER_PREFIX.length());
        // 老 token 无 ut claim 时 userType() 返回 0（超管），存量账号不受影响
        if (jwtUtil.userType(token) != 1 || READ_ONLY_WHITELIST.contains(request.getRequestURI())) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.failed(DENY_MESSAGE)));
        return false;
    }
}
