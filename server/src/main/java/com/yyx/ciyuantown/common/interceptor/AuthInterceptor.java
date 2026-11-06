package com.yyx.ciyuantown.common.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.sys.entity.AdminUser;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.api.ResultCode;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 登录鉴权拦截器：校验 Authorization: Bearer &lt;token&gt;
 *
 * <p>校验成功后把 userId 放入 request attribute；失败时返回 HTTP 200 + 业务码：
 * 缺失 token 返回 8888（前端登出），token 无效/过期返回 9999（前端自动刷新）</p>
 *
 * <p>token 版本校验（官方账号踢下线）：比对 token 的 ver 与 Redis {@code ct:admin:ver:{id}}，
 * 以 admin_user 为准并回写 Redis（TTL 10 分钟）。禁用/重置密码会递增 token_version 并写回该 key，
 * 已签发的 token 立即失效，无需等 access token 过期。禁用必递增版本，因此版本匹配即账号可用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** request attribute key：当前登录用户 id */
    public static final String ATTR_USER_ID = "userId";

    static final String BEARER_PREFIX = "Bearer ";

    /** Redis key：管理员 token 版本（与 app 端 ct:session:ver:{id} 语义对齐） */
    private static final String VER_KEY = "ct:admin:ver:";

    private static final Duration VER_TTL = Duration.ofMinutes(10);

    private final JwtUtil jwtUtil;

    private final ObjectMapper objectMapper;

    private final AdminUserMapper adminUserMapper;

    private final RedisHotspotService redis;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeError(response, ResultCode.UNAUTHORIZED);
            return false;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        Long userId = jwtUtil.verifyAndGetUserId(token, "access");
        if (userId == null) {
            writeError(response, ResultCode.TOKEN_EXPIRED);
            return false;
        }
        if (!sessionValid(userId, jwtUtil.tokenVersion(token))) {
            // 版本不匹配/账号被禁用：8888 让前端登出（踢下线语义）
            writeError(response, ResultCode.UNAUTHORIZED);
            return false;
        }
        request.setAttribute(ATTR_USER_ID, userId);
        return true;
    }

    /** 校验 token 版本与账号状态；数据库为权威来源，Redis 仅缓存版本；查库失败时拒绝访问 */
    private boolean sessionValid(Long userId, Integer tokenVersion) {
        int ver = tokenVersion == null ? 0 : tokenVersion;
        try {
            AdminUser user = adminUserMapper.selectById(userId);
            if (user == null || user.hasBeenDeleted() || (user.getStatus() != null && user.getStatus() == 1)) {
                return false;
            }
            int dbVer = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
            // Redis 写入失败或并发提交后的回调乱序不能恢复旧凭据：以数据库版本为准。
            if (!redis.integer(VER_KEY + userId).equals(Optional.of(dbVer)))
                redis.put(VER_KEY + userId, String.valueOf(dbVer), VER_TTL);
            return Objects.equals(dbVer, ver);
        } catch (Exception e) {
            log.warn("管理员会话校验失败: userId={}", userId, e);
            return false;
        }
    }

    private void writeError(HttpServletResponse response, ResultCode resultCode) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.failed(resultCode)));
    }
}
