package com.yyx.ciyuantown;
import cn.hutool.jwt.JWT;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
class JwtAudienceTests {
    private JwtUtil jwt;
    private final String secret = "p2-test-signing-key-never-use-in-production-1234567890";
    @BeforeEach void setup() {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt,"secret",secret);
        ReflectionTestUtils.setField(jwt,"accessTokenExpireSeconds",7200L);
        ReflectionTestUtils.setField(jwt,"refreshTokenExpireSeconds",604800L);
    }
    @Test void tokenAudiencesAndTypesCannotBeExchanged() {
        String admin = jwt.createAccessToken(1L), app = jwt.createAccessToken(1L,"app");
        assertEquals(1L,jwt.verifyAndGetUserId(admin,"access"));
        assertEquals(1L,jwt.verifyAndGetUserId(app,"access","app"));
        assertNull(jwt.verifyAndGetUserId(app,"access"));
        assertNull(jwt.verifyAndGetUserId(admin,"access","app"));
        assertNull(jwt.verifyAndGetUserId(jwt.createRefreshToken(1L,"app"),"access","app"));
        assertNull(jwt.verifyAndGetUserId(jwt.createRefreshToken(1L),"refresh","app"));
    }
    @Test void legacyTamperedAndExpiredTokensAreRejected() {
        String legacy = JWT.create().setPayload("userId",1L).setPayload("type","access")
            .setExpiresAt(new Date(System.currentTimeMillis()+60000)).setKey(secret.getBytes(StandardCharsets.UTF_8)).sign();
        assertNull(jwt.verifyAndGetUserId(legacy,"access"));
        assertNull(jwt.verifyAndGetUserId(legacy,"access","app"));
        assertNull(jwt.verifyAndGetUserId("broken","access","app"));
        ReflectionTestUtils.setField(jwt,"accessTokenExpireSeconds",-10L);
        assertNull(jwt.verifyAndGetUserId(jwt.createAccessToken(1L,"app"),"access","app"));
    }
}
