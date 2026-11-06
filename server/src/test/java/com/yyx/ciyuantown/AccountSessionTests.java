package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.sys.entity.AdminUser;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.yyx.ciyuantown.common.interceptor.*;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A stale Redis version must never authorize revoked credentials or reject the current version. */
class AccountSessionTests {
    @Test void deletedRecordsAreRejectedEvenWithMatchingCachedAndDatabaseVersions() throws Exception {
        var jwt=mock(JwtUtil.class);var redis=mock(RedisHotspotService.class);
        var admins=mock(AdminUserMapper.class);var apps=mock(AppUserMapper.class);
        when(jwt.verifyAndGetUserId("token","access")).thenReturn(1L);
        when(jwt.verifyAndGetUserId("token","access","app")).thenReturn(1L);
        when(jwt.tokenVersion("token")).thenReturn(0);
        var admin=new AdminUser();admin.setId(1L);admin.setStatus(0);admin.setTokenVersion(0);admin.setDeleted(true);
        var app=new AppUser();app.setId(1L);app.setStatus(0);app.setTokenVersion(0);app.setDeleted(true);
        when(admins.selectById(1L)).thenReturn(admin);when(apps.selectById(1L)).thenReturn(app);
        when(redis.integer(anyString())).thenReturn(Optional.of(0));
        var adminGuard=new AuthInterceptor(jwt,new ObjectMapper(),admins,redis);
        var appGuard=new AppAuthInterceptor(jwt,apps,new ObjectMapper(),redis,mock(com.yyx.ciyuantown.business.appuser.service.UserActivityService.class));
        var handler=new HandlerMethod(new GuardedController(),GuardedController.class.getMethod("guarded"));
        for(boolean cacheAvailable:new boolean[]{true,false}) {
            if(!cacheAvailable)when(redis.integer(anyString())).thenReturn(Optional.empty());
            assertFalse(adminGuard.preHandle(request(),new MockHttpServletResponse(),new Object()));
            var request=request();assertFalse(appGuard.preHandle(request,new MockHttpServletResponse(),handler));
            assertNull(request.getAttribute(AuthInterceptor.ATTR_USER_ID));
        }
    }
    static class GuardedController { @AppLoginRequired public void guarded() {} }
    MockHttpServletRequest request() {
        var request=new MockHttpServletRequest("GET","/guarded"); request.addHeader("Authorization","Bearer token"); return request;
    }
    @Test void adminDatabaseVersionWinsOverStaleCache() throws Exception {
        var jwt=mock(JwtUtil.class);var redis=mock(RedisHotspotService.class);var users=mock(AdminUserMapper.class);
        when(jwt.verifyAndGetUserId("token","access")).thenReturn(1L);
        when(jwt.tokenVersion("token")).thenReturn(0,1);
        when(redis.integer("ct:admin:ver:1")).thenReturn(Optional.of(0));
        var user=new AdminUser();user.setId(1L);user.setStatus(0);user.setTokenVersion(1);
        when(users.selectById(1L)).thenReturn(user);
        var interceptor=new AuthInterceptor(jwt,new ObjectMapper(),users,redis);
        assertFalse(interceptor.preHandle(request(),new MockHttpServletResponse(),new Object()));
        assertTrue(interceptor.preHandle(request(),new MockHttpServletResponse(),new Object()));
        when(users.selectById(1L)).thenThrow(new IllegalStateException("test database unavailable"));
        assertFalse(interceptor.preHandle(request(),new MockHttpServletResponse(),new Object()));
    }
    @Test void appDatabaseVersionWinsOverStaleCache() throws Exception {
        var jwt=mock(JwtUtil.class);var redis=mock(RedisHotspotService.class);var users=mock(AppUserMapper.class);
        when(jwt.verifyAndGetUserId("token","access","app")).thenReturn(1L);
        when(jwt.tokenVersion("token")).thenReturn(0,1);
        when(redis.integer("ct:session:ver:1")).thenReturn(Optional.of(0));
        var user=new AppUser();user.setId(1L);user.setStatus(0);user.setTokenVersion(1);
        when(users.selectById(1L)).thenReturn(user);
        var interceptor=new AppAuthInterceptor(jwt,users,new ObjectMapper(),redis,mock(com.yyx.ciyuantown.business.appuser.service.UserActivityService.class));
        var handler=new HandlerMethod(new GuardedController(),GuardedController.class.getMethod("guarded"));
        assertFalse(interceptor.preHandle(request(),new MockHttpServletResponse(),handler));
        var current=request();assertTrue(interceptor.preHandle(current,new MockHttpServletResponse(),handler));
        assertEquals(1L,current.getAttribute(AuthInterceptor.ATTR_USER_ID));
    }
}
