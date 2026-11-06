package com.yyx.ciyuantown;

import cn.hutool.crypto.digest.BCrypt;
import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.business.appauth.service.*;
import com.yyx.ciyuantown.business.appuser.dto.AppUserCreateParam;
import com.yyx.ciyuantown.business.appuser.service.*;
import com.yyx.ciyuantown.business.sys.dto.AdminUserCreateParam;
import com.yyx.ciyuantown.business.sys.service.AdminUserAdminService;
import com.yyx.ciyuantown.common.exception.BizException;
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
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** scripts/verify-user-accounts.py creates a disposable MySQL database; never run against the project database. */
@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB",matches="true")
@SpringBootTest(properties={"ciyuantown.redis.enabled=false","spring.mail.username=account-test@example.invalid",
    "spring.mail.password=test-only","ciyuantown.upload-dir=./target/account-test-uploads"})
@AutoConfigureMockMvc
@Transactional
class UserAccountManagementTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtUtil jwt;
    @Autowired LoginAccountService accounts;
    @Autowired AppUserService users;
    @Autowired AdminUserAdminService officials;
    @Autowired AppAuthService auth;
    @MockitoBean EmailCodeService codes;
    long superId;
    String marker;
    static final String PASSWORD="12345678";
    @BeforeEach void setup() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_"));
        marker=UUID.randomUUID().toString().replace("-","").substring(0,10);
        superId=new SimpleJdbcInsert(jdbc).withTableName("admin_user").usingGeneratedKeyColumns("id")
            .usingColumns("username","password","nickname","user_type")
            .executeAndReturnKey(Map.of("username","super_"+marker,"password",BCrypt.hashpw(PASSWORD),"nickname","测试管理员","user_type",0)).longValue();
        accounts.reserve(LoginAccountService.ADMIN,superId,"super_"+marker);
    }
    String qq() { return "9"+String.format("%010d",ThreadLocalRandom.current().nextLong(10_000_000_000L)); }
    String bearer(long id,int ver,int type) { return "Bearer "+jwt.createAccessToken(id,"admin",ver,type); }
    String superToken() { return bearer(superId,0,0); }
    JsonNode post(String url,String token,Object body) throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
            .contentType("application/json").content(json.writeValueAsString(body));
        if(token!=null) request.header("Authorization",token);
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }
    JsonNode get(String url,String token) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
            .header("Authorization",token)).andReturn().getResponse().getContentAsString());
    }
    JsonNode login(String name,String password) throws Exception { return post("/app/auth/loginByPassword",null,Map.of("qqNumber",name,"password",password)); }
    long app(String qq,String account,String password) {
        return Long.parseLong(users.createByAdmin(new AppUserCreateParam(qq,"测试町友",password,account)).id());
    }
    long official(String name) { return Long.parseLong(officials.create(superId,new AdminUserCreateParam(name,PASSWORD,"官方测试")).id()); }
    @Test void ordinaryRenamePasswordResetAndQqCodeCompatibility() throws Exception {
        String qq=qq(); long id=app(qq,null,null);
        assertEquals("1000",login(qq,PASSWORD).path("code").asText());
        String oldToken=jwt.createAccessToken(id,"app",0),oldRefresh=jwt.createRefreshToken(id,"app",0);
        assertEquals("0000",post("/app-user/resetPassword",superToken(),Map.of("id",id,"password",PASSWORD)).path("code").asText());
        assertEquals("0000",login(qq,PASSWORD).path("code").asText());
        String name="user_"+marker;
        assertEquals("0000",post("/app-user/updateAccount",superToken(),Map.of("id",id,"account",name)).path("code").asText());
        assertEquals("1000",login(qq,PASSWORD).path("code").asText());
        var result=login(name.toUpperCase(Locale.ROOT),PASSWORD);
        assertEquals("0000",result.path("code").asText()); assertEquals(name,result.at("/data/userInfo/account").asText());
        assertEquals("8888",get("/app/user/me","Bearer "+oldToken).path("code").asText());
        assertEquals("8888",post("/app/auth/refreshToken",null,Map.of("refreshToken",oldRefresh)).path("code").asText());
        assertEquals(Long.toString(id),post("/app/auth/login",null,Map.of("qqNumber",qq,"code","123456")).at("/data/userInfo/userId").asText());
        verify(codes).verify(qq,"123456");
        assertEquals(qq,jdbc.queryForObject("SELECT qq_number FROM app_user WHERE id=?",String.class,id));
        assertEquals(qq+"@qq.com",jdbc.queryForObject("SELECT email FROM app_user WHERE id=?",String.class,id));
        assertTrue(BCrypt.checkpw(PASSWORD,jdbc.queryForObject("SELECT password FROM app_user WHERE id=?",String.class,id)));
    }
    @Test void managementCreationContractsAndAccountSearch() throws Exception {
        String qq=qq(),name="created_"+marker;
        var created=post("/app-user/create",superToken(),Map.of("qqNumber",qq,"account",name));
        assertEquals("0000",created.path("code").asText());
        assertEquals(name,created.at("/data/account").asText());
        assertFalse(get("/app-user/detail?id="+created.at("/data/id").asText(),superToken()).at("/data/hasPassword").asBoolean());
        var list=post("/app-user/list",superToken(),Map.of("pageNum",1,"pageSize",10,"keyword",name));
        assertEquals(1,list.at("/data/total").asInt());
        assertEquals(name,list.at("/data/records/0/account").asText());
        var official=post("/admin-user/create",superToken(),Map.of("account","official_"+marker,"password",PASSWORD));
        assertEquals("0000",official.path("code").asText());
        assertEquals("official_"+marker,official.at("/data/username").asText());
        String oldRefresh=jwt.createRefreshToken(Long.parseLong(official.at("/data/id").asText()),"admin",0,1);
        assertEquals("0000",post("/admin-user/resetPassword",superToken(),Map.of("id",official.at("/data/id").asText(),"password","87654321")).path("code").asText());
        assertEquals("8888",post("/auth/refreshToken",null,Map.of("refreshToken",oldRefresh)).path("code").asText());
    }
    @Test void officialRenameKeepsCommunityAndInvalidatesBothAudiences() throws Exception {
        long id=official("official_"+marker);
        var first=login("official_"+marker,PASSWORD); assertEquals("0000",first.path("code").asText());
        String community=first.at("/data/userInfo/userId").asText();
        assertTrue(first.at("/data/userInfo/isOfficial").asBoolean());
        assertTrue(first.at("/data/userInfo/hasPassword").asBoolean());
        String oldAdmin=bearer(id,0,1);
        assertEquals("0000",post("/app-user/updateAccount",superToken(),Map.of("id",community,"account","76543210")).path("code").asText());
        assertEquals("1000",login("official_"+marker,PASSWORD).path("code").asText());
        assertEquals(community,login("76543210",PASSWORD).at("/data/userInfo/userId").asText());
        assertEquals("8888",get("/auth/getUserInfo",oldAdmin).path("code").asText());
        assertEquals("8888",get("/app/user/me","Bearer "+first.at("/data/token").asText()).path("code").asText());
        assertEquals("8888",post("/app/auth/refreshToken",null,Map.of("refreshToken",first.at("/data/refreshToken").asText())).path("code").asText());
        String oldApp=login("76543210",PASSWORD).at("/data/token").asText();
        assertEquals("0000",post("/app-user/resetPassword",superToken(),Map.of("id",community,"password","87654321")).path("code").asText());
        assertEquals("1000",login("76543210",PASSWORD).path("code").asText());
        assertEquals(community,login("76543210","87654321").at("/data/userInfo/userId").asText());
        assertEquals("8888",get("/app/user/me","Bearer "+oldApp).path("code").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE official_admin_id=?",Integer.class,id));
        assertNull(jdbc.queryForObject("SELECT qq_number FROM app_user WHERE id=?",String.class,community));
        assertNull(jdbc.queryForObject("SELECT password FROM app_user WHERE id=?",String.class,community));
        assertEquals("1000",post("/app/user/password/set","Bearer "+login("76543210","87654321").at("/data/token").asText(),Map.of("password",PASSWORD)).path("code").asText());
    }
    @Test void crossTypeDuplicatesRejectWithoutChangingIdentity() throws Exception {
        String qq=qq(); long app=app(qq,"taken_"+marker,PASSWORD); long official=official("other_"+marker);
        assertEquals("1000",post("/admin-user/updateAccount",superToken(),Map.of("id",official,"account",("taken_"+marker).toUpperCase(Locale.ROOT))).path("code").asText());
        assertEquals("other_"+marker,jdbc.queryForObject("SELECT username FROM admin_user WHERE id=?",String.class,official));
        assertEquals("taken_"+marker,jdbc.queryForObject("SELECT username FROM app_user WHERE id=?",String.class,app));
    }
    @Test void invalidAccountAndPasswordDoNotMutate() throws Exception {
        long app=app(qq(),null,PASSWORD);
        for(String name:List.of("abc","has space","账号测试","a".repeat(21)))
            assertEquals("1000",post("/app-user/updateAccount",superToken(),Map.of("id",app,"account",name)).path("code").asText());
        for(String password:List.of("1234567","12345678 ","1234\n5678","12\n34\n5678","12345678\u00a0","1".repeat(21)))
            assertEquals("1000",post("/app-user/resetPassword",superToken(),Map.of("id",app,"password",password)).path("code").asText());
        assertEquals(0,jdbc.queryForObject("SELECT token_version FROM app_user WHERE id=?",Integer.class,app));
    }
    @Test void readonlyCanReadAppDetailButCannotReadOfficialsOrWrite() throws Exception {
        long app=app(qq(),null,null),official=official("reader_"+marker); String token=bearer(official,0,1);
        assertEquals("0000",get("/app-user/detail?id="+app,token).path("code").asText());
        assertEquals("1000",get("/admin-user/detail?id="+official,token).path("code").asText());
        assertEquals("1000",post("/app-user/updateAccount",token,Map.of("id",app,"account","changed_name")).path("code").asText());
        assertEquals("1000",post("/app-user/resetPassword",token,Map.of("id",app,"password",PASSWORD)).path("code").asText());
        assertEquals("1000",post("/admin-user/updateAccount",superToken(),Map.of("id",superId,"account","super_changed")).path("code").asText());
        assertEquals("1000",login("super_"+marker,PASSWORD).path("code").asText());
    }
    @Test void detailsAreSafeAndStatisticsOnlyCountPublishedPosts() throws Exception {
        long owner=app(qq(),"stats_"+marker,PASSWORD),peer=app(qq(),null,null);
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,status) VALUES(?,?,?,1),(?,?,?,2)",owner,"作者","已发布",owner,"作者","已下架");
        long published=jdbc.queryForObject("SELECT id FROM post WHERE author_id=? AND status=1",Long.class,owner);
        long hidden=jdbc.queryForObject("SELECT id FROM post WHERE author_id=? AND status=2",Long.class,owner);
        jdbc.update("INSERT INTO user_like(user_id,post_id) VALUES(?,?),(?,?)",peer,published,peer,hidden);
        jdbc.update("INSERT INTO user_favorite(user_id,post_id) VALUES(?,?),(?,?)",owner,published,owner,hidden);
        jdbc.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?),(?,?)",peer,owner,owner,peer);
        var detail=get("/app-user/detail?id="+owner,superToken()).path("data");
        for(String field:List.of("postCount","followerCount","followingCount","receivedLikeCount","favoriteCount"))
            assertEquals(1,detail.path("statistics").path(field).asInt(),field);
        assertFalse(detail.has("password")); assertTrue(detail.path("hasPassword").asBoolean());
        assertFalse(detail.toString().contains("$2"));
        long official=official("unlinked_"+marker);
        var unlinked=get("/admin-user/detail?id="+official,superToken()).path("data");
        assertTrue(unlinked.path("community").isNull()); assertEquals(0,unlinked.path("statistics").path("postCount").asInt());
        assertFalse(unlinked.has("password"));
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void numericAccountBlocksNewQqRegistration() throws Exception {
        String name=qq(); official(name);
        assertEquals("1000",post("/app/auth/login",null,Map.of("qqNumber",name,"code","123456")).path("code").asText());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE qq_number=?",Integer.class,name));
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentCreationRollsBackLosingIdentity() throws Exception {
        String name="race_"+marker;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            Callable<Boolean> first=()-> { gate.await(); try { app(qq(),name,PASSWORD); return true; } catch(BizException e) { return false; } };
            Callable<Boolean> second=()-> { gate.await(); try { official(name.toUpperCase(Locale.ROOT)); return true; } catch(BizException e) { return false; } };
            var a=pool.submit(first);var b=pool.submit(second);gate.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM login_account WHERE account=?",Integer.class,name));
            assertEquals(1,jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM app_user WHERE username=?)+(SELECT COUNT(*) FROM admin_user WHERE username=?)",Integer.class,name,name));
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentRenamesKeepOneOwnerAndRollbackLoser() throws Exception {
        long app=app(qq(),"before_"+marker,PASSWORD),official=official("prior_"+marker);
        String target="rename_"+marker;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()-> { gate.await(); try { accounts.updateAppAccount(superId,app,target); return true; } catch(BizException e) { return false; } });
            var b=pool.submit(()-> { gate.await(); try { accounts.updateOfficialAccount(superId,official,target.toUpperCase(Locale.ROOT)); return true; } catch(BizException e) { return false; } });
            gate.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM login_account WHERE account=?",Integer.class,target));
            assertEquals(1,jdbc.queryForObject("SELECT (SELECT token_version FROM app_user WHERE id=?)+(SELECT token_version FROM admin_user WHERE id=?)",Integer.class,app,official));
            assertEquals(1,jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM app_user WHERE username=?)+(SELECT COUNT(*) FROM admin_user WHERE username=?)",Integer.class,target,target));
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentOfficialFirstLoginsCreateOneCommunity() throws Exception {
        long official=official("parallel_"+marker);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->auth.loginByPassword("parallel_"+marker,PASSWORD));
            var b=pool.submit(()->auth.loginByPassword("parallel_"+marker,PASSWORD));
            assertEquals(a.get(15,TimeUnit.SECONDS).userInfo().userId(),b.get(15,TimeUnit.SECONDS).userInfo().userId());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE official_admin_id=?",Integer.class,official));
        }
    }
}
