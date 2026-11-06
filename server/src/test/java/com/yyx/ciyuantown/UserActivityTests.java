package com.yyx.ciyuantown;

import cn.hutool.crypto.digest.BCrypt;
import com.yyx.ciyuantown.business.appauth.service.AppAuthService;
import com.yyx.ciyuantown.business.appauth.service.EmailCodeService;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.appuser.service.LoginAccountService;
import com.yyx.ciyuantown.business.appuser.service.UserActivityService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB",matches="true")
@SpringBootTest(properties="ciyuantown.redis.enabled=false")
@AutoConfigureMockMvc
class UserActivityTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserMapper users;
    @Autowired UserActivityService activity;
    @Autowired AppAuthService auth;
    @Autowired LoginAccountService accounts;
    @Autowired JwtUtil jwt;
    @Autowired MockMvc mvc;
    @MockitoBean EmailCodeService codes;
    long id;
    String qq;
    final LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));

    @BeforeEach void seed() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_"));
        qq="9"+String.format("%010d",ThreadLocalRandom.current().nextLong(10_000_000_000L));
        id=new SimpleJdbcInsert(jdbc).withTableName("app_user").usingGeneratedKeyColumns("id")
            .usingColumns("qq_number","email","nickname","password").executeAndReturnKey(
                Map.of("qq_number",qq,"email",qq+"@qq.com","nickname","活跃测试","password",BCrypt.hashpw("12345678"))).longValue();
        accounts.reserve(LoginAccountService.APP,id,qq);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM login_account WHERE owner_type=? AND owner_id=?",LoginAccountService.APP,id);
        jdbc.update("DELETE FROM app_user WHERE id=?",id);
    }
    long days() { return jdbc.queryForObject("SELECT active_days FROM app_user WHERE id=?",Long.class,id); }
    String token() { return "Bearer "+jwt.createAccessToken(id,"app",0); }

    @Test void codePasswordAndTokenRefreshCountTheSameDayOnce() {
        auth.login(qq,"123456"); assertEquals(1,days());
        auth.loginByPassword(qq,"12345678"); assertEquals(1,days());
        auth.refreshToken(jwt.createRefreshToken(id,"app",0)); assertEquals(1,days());
        assertEquals(today,users.selectById(id).getLastActiveDate());
    }
    @Test void rememberedSessionCountsANewDayWithoutReLoginAndSkipsAbsentDays() throws Exception {
        jdbc.update("UPDATE app_user SET active_days=4,last_active_date=?,last_login_time=? WHERE id=?",
            today.minusDays(3),today.minusDays(3).atTime(23,59),id);
        mvc.perform(get("/app/user/statistics").header("Authorization",token()))
            .andExpect(jsonPath("$.code").value("0000")).andExpect(jsonPath("$.data.activeDays").value(5));
        mvc.perform(get("/app/user/me").header("Authorization",token())).andExpect(jsonPath("$.code").value("0000"));
        assertEquals(5,days());
        assertEquals(today.minusDays(3).atTime(23,59),users.selectById(id).getLastLoginTime());
    }
    @Test void simultaneousDevicesWithStaleSnapshotsOnlyAddOneDay() throws Exception {
        jdbc.update("UPDATE app_user SET active_days=7,last_active_date=? WHERE id=?",today.minusDays(1),id);
        AppUser stale=users.selectById(id);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var jobs=new ArrayList<Callable<Void>>();
            for(int i=0;i<16;i++)jobs.add(()->{activity.record(stale);return null;});
            for(var result:pool.invokeAll(jobs))result.get();
        }
        assertEquals(8,days());
        activity.record(users.selectById(id));assertEquals(8,days());
    }
    @Test void invalidAnonymousAndDisabledSessionsCannotCountActivity() throws Exception {
        mvc.perform(get("/app/user/me"));
        mvc.perform(get("/app/user/me").header("Authorization","Bearer invalid"));
        assertEquals(0,days());
        jdbc.update("UPDATE app_user SET status=1 WHERE id=?",id);
        mvc.perform(get("/app/user/me").header("Authorization",token()));
        activity.record(users.selectById(id));assertEquals(0,days());
    }
}
