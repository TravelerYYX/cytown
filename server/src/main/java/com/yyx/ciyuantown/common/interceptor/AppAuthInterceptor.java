package com.yyx.ciyuantown.common.interceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.appuser.service.UserActivityService;
import com.yyx.ciyuantown.common.api.*;
import com.yyx.ciyuantown.common.util.JwtUtil;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;
import java.time.Duration;
@Component
@RequiredArgsConstructor
public class AppAuthInterceptor implements HandlerInterceptor {
    public static final String ATTR_TOKEN_VERSION="appTokenVersion";
    private final JwtUtil jwt;
    private final AppUserMapper users;
    private final ObjectMapper json;
    private final RedisHotspotService redis;
    private final UserActivityService activity;
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        if ("OPTIONS".equals(request.getMethod())) return true;
        boolean required = handler instanceof HandlerMethod h &&
            (AnnotatedElementUtils.hasAnnotation(h.getMethod(),AppLoginRequired.class)
             || AnnotatedElementUtils.hasAnnotation(h.getBeanType(),AppLoginRequired.class));
        String header = request.getHeader("Authorization");
        boolean present = header != null && !header.isBlank();
        if(present && request.getRequestURI().startsWith("/app/notice/")) required=true;
        Long id = header != null && header.startsWith("Bearer ")
            ? jwt.verifyAndGetUserId(header.substring(7),"access","app") : null;
        boolean disabled = false;
        if (id != null) {
            String token=header.substring(7);
            String jti=jwt.tokenId(token);
            if(jti!=null&&redis.string("ct:jwt:blacklist:"+jti).isPresent()){disabled=true;id=null;}
            else{
                Integer tokenVersion=jwt.tokenVersion(token);
                var user=users.selectById(id);
                int dbVersion=user==null||user.getTokenVersion()==null?0:user.getTokenVersion();
                if(user==null||user.hasBeenDeleted()||user.getStatus()!=0||!java.util.Objects.equals(dbVersion,tokenVersion)){disabled=true;id=null;}
                else {
                    activity.record(user);
                    if(!redis.integer("ct:session:ver:"+id).equals(java.util.Optional.of(dbVersion)))
                        redis.put("ct:session:ver:"+id,String.valueOf(dbVersion),Duration.ofMinutes(10));
                }
            }
        }
        if (id != null) {request.setAttribute(AuthInterceptor.ATTR_USER_ID,id);request.setAttribute(ATTR_TOKEN_VERSION,jwt.tokenVersion(header.substring(7)));}
        if (!required || id != null) return true;
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(Result.failed(
            !present || disabled ? ResultCode.UNAUTHORIZED : ResultCode.TOKEN_EXPIRED)));
        return false;
    }
}
