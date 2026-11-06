package com.yyx.ciyuantown;

import cn.hutool.crypto.digest.BCrypt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.appauth.service.AppAuthService;
import com.yyx.ciyuantown.business.appauth.service.EmailCodeService;
import com.yyx.ciyuantown.business.appuser.dto.AppUserCreateParam;
import com.yyx.ciyuantown.business.appuser.service.*;
import com.yyx.ciyuantown.business.auth.service.AuthService;
import com.yyx.ciyuantown.business.interaction.service.InteractionService;
import com.yyx.ciyuantown.business.post.dto.PostQueryParam;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.business.post.service.CommentService;
import com.yyx.ciyuantown.business.publish.service.PublishService;
import com.yyx.ciyuantown.business.publish.service.PublishMediaService;
import com.yyx.ciyuantown.business.sys.dto.AdminUserCreateParam;
import com.yyx.ciyuantown.business.sys.service.AdminUserAdminService;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs exclusively in the disposable MySQL database supplied by verify-user-accounts.py. */
@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB",matches="true")
@SpringBootTest(properties={"ciyuantown.redis.enabled=false","spring.mail.username=account-test@example.invalid",
    "spring.mail.password=test-only","ciyuantown.upload-dir=./target/account-test-uploads"})
@AutoConfigureMockMvc
@Import(PostAuthorNicknameTests.Caches.class)
class UserDeletionTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired JwtUtil jwt;
    @Autowired AppUserService users;
    @Autowired AdminUserAdminService officials;
    @Autowired LoginAccountService accounts;
    @Autowired UserDeletionService deletions;
    @Autowired AppAuthService appAuth;
    @Autowired AuthService adminAuth;
    @Autowired InteractionService interactions;
    @Autowired PostService posts;
    @Autowired CommentService comments;
    @Autowired PublishService publish;
    @Autowired PublishMediaService media;
    @MockitoSpyBean CacheManager caches;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean EmailCodeService codes;
    long operator;
    String marker;
    static final String PASSWORD="12345678";

    @BeforeEach void seed() {
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_"));
        marker=UUID.randomUUID().toString().replace("-","").substring(0,10);
        operator=insert("admin_user",Map.of("username","super_"+marker,"password",BCrypt.hashpw(PASSWORD),"user_type",0));
        accounts.reserve(LoginAccountService.ADMIN,operator,"super_"+marker);
        clearCaches();
    }
    @AfterEach void clearCaches() {
        reset(caches);
        for (String name:caches.getCacheNames()) caches.getCache(name).clear();
    }
    long insert(String table,Map<String,Object> values) {
        return new SimpleJdbcInsert(db).withTableName(table).usingGeneratedKeyColumns("id")
            .usingColumns(values.keySet().toArray(String[]::new)).executeAndReturnKey(values).longValue();
    }
    String qq() { return "9"+String.format("%010d",ThreadLocalRandom.current().nextLong(10_000_000_000L)); }
    long app(String qq,String name) { return Long.parseLong(users.createByAdmin(new AppUserCreateParam(qq,"原昵称",PASSWORD,name)).id()); }
    long official(String name) { return Long.parseLong(officials.create(operator,new AdminUserCreateParam(name,PASSWORD,"原官方昵称")).id()); }
    String superToken() { return "Bearer "+jwt.createAccessToken(operator,"admin",0,0); }
    String appToken(long id) { return "Bearer "+jwt.createAccessToken(id,"app",0); }
    JsonNode post(String path,String token,Object body) throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
            .contentType("application/json").content(json.writeValueAsString(body));
        if(token!=null)request.header("Authorization",token);
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }
    JsonNode get(String path,String token) throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
        if(token!=null)request.header("Authorization",token);
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }
    void success(JsonNode result) { assertEquals("0000",result.path("code").asText(),result.toString()); }
    void failed(JsonNode result) { assertNotEquals("0000",result.path("code").asText(),result.toString()); }
    int flag(String table,String column,long id) { return db.queryForObject("SELECT "+column+" FROM "+table+" WHERE id=?",Integer.class,id); }

    @Test void ordinaryDeletionHidesManagementAndRejectsOldSessionsAndAllEdits() throws Exception {
        String qq=qq(),name="app_"+marker;
        long id=app(qq,name);
        users.me(id); // Warm the profile cache path.
        String refresh=jwt.createRefreshToken(id,"app",0);
        success(post("/app-user/delete",superToken(),Map.of("id",Long.toString(id))));
        assertEquals(1,flag("app_user","deleted",id));
        assertEquals(1,flag("app_user","status",id));
        assertEquals(1,flag("app_user","token_version",id));
        assertEquals(operator,db.queryForObject("SELECT deleted_by FROM app_user WHERE id=?",Long.class,id));
        assertNotNull(db.queryForObject("SELECT deleted_at FROM app_user WHERE id=?",java.sql.Timestamp.class,id));
        assertEquals(qq,db.queryForObject("SELECT qq_number FROM app_user WHERE id=?",String.class,id));
        assertNull(accounts.find(name));
        assertEquals(0,post("/app-user/list",superToken(),Map.of("pageNum",1,"pageSize",10,"keyword",name)).at("/data/total").asInt());
        failed(get("/app-user/detail?id="+id,superToken()));
        assertEquals("8888",get("/app/user/me",appToken(id)).path("code").asText());
        assertEquals("8888",post("/app/auth/refreshToken",null,Map.of("refreshToken",refresh)).path("code").asText());
        failed(post("/app/auth/loginByPassword",null,Map.of("qqNumber",name,"password",PASSWORD)));
        failed(post("/app-user/updateStatus",superToken(),Map.of("id",id,"status",0)));
        failed(post("/app-user/updateAccount",superToken(),Map.of("id",id,"account","other_"+marker)));
        failed(post("/app-user/resetPassword",superToken(),Map.of("id",id,"password","87654321")));
        assertThrows(BizException.class,()->users.updateNickname(id,"绕过登录的直接调用"));
        assertThrows(BizException.class,()->users.me(id));
        assertEquals(1,flag("app_user","deleted",id));
    }

    @ParameterizedTest
    @CsvSource({"app-user,post-list", "app-user,post-detail", "admin-user,post-list", "admin-user,post-detail"})
    void cacheFailureAfterCommitStillReturnsDeletionSuccess(String route, String failedCache) throws Exception {
        String name="cache_"+marker;
        boolean official=route.equals("admin-user");
        long id=official ? official(name) : app(qq(),name);
        long community=official ? Long.parseLong(appAuth.loginByPassword(name,PASSWORD).userInfo().userId()) : id;
        String healthyCache=failedCache.equals("post-list") ? "post-detail" : "post-list";
        Cache healthy=spy(caches.getCache(healthyCache));
        healthy.put("delete-marker","warm");
        Cache broken=mock(Cache.class);
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("test cache unavailable"))
            .when(broken).clear();
        doReturn(broken).when(caches).getCache(failedCache);
        doReturn(healthy).when(caches).getCache(healthyCache);

        success(post("/"+route+"/delete",superToken(),Map.of("id",id)));
        assertEquals(1,flag("app_user","deleted",community));
        assertEquals(1,flag("app_user","token_version",community));
        if (official) assertEquals(1,flag("admin_user","deleted",id));
        assertNull(accounts.find(name));
        assertEquals(0,post("/"+route+"/list",superToken(),Map.of("pageNum",1,"pageSize",10,"keyword",name)).at("/data/total").asInt());
        assertEquals("8888",get("/app/user/me",appToken(community)).path("code").asText());
        verify(broken).clear();
        verify(healthy).clear();
        assertNull(healthy.get("delete-marker"));
        success(post("/"+route+"/delete",superToken(),Map.of("id",id)));
        assertEquals(1,flag("app_user","token_version",community));
    }

    @Test void disabledUsersCanBeDeletedAndOriginalQqAndAccountCanBeReused() throws Exception {
        String qq=qq(),name="reuse_"+marker;
        long old=app(qq,name);
        long historicalPost=insert("post",Map.of("author_id",old,"author_nickname","旧用户","title","保留原归属","status",1));
        users.updateStatus(old,1);
        deletions.deleteApp(operator,old);
        long fresh=app(qq,name);
        assertNotEquals(old,fresh);
        assertNotEquals(db.queryForObject("SELECT public_uid FROM app_user WHERE id=?",Long.class,old),
            db.queryForObject("SELECT public_uid FROM app_user WHERE id=?",Long.class,fresh));
        assertEquals(Long.toString(fresh),appAuth.loginByPassword(name,PASSWORD).userInfo().userId());
        success(post("/app-user/delete",superToken(),Map.of("id",old)));
        assertEquals(0,flag("app_user","deleted",fresh));
        assertEquals(fresh,accounts.find(name).getOwnerId());
        assertEquals(2,flag("app_user","token_version",old)); // Repeated delete makes no further changes.
        assertEquals(old,db.queryForObject("SELECT author_id FROM post WHERE id=?",Long.class,historicalPost));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM post WHERE author_id=?",Integer.class,fresh));
        deletions.deleteApp(operator,fresh);
        long third=app(qq,name);
        assertNotEquals(fresh,third);
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM app_user WHERE qq_number=?",Integer.class,qq));
    }

    @Test void codeLoginCreatesNewIdentityAfterDeletion() {
        String qq=qq(); long old=app(qq,null);
        deletions.deleteApp(operator,old);
        long fresh=Long.parseLong(appAuth.login(qq,"123456").userInfo().userId());
        assertNotEquals(old,fresh);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM app_user WHERE qq_number=? AND deleted=FALSE",Integer.class,qq));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM app_user WHERE qq_number=?",Integer.class,qq));
    }

    @Test void directBusinessWritesRejectDeletedIdentityBeforeWritingOrUploading() {
        long id=app(qq(),null);
        deletions.deleteApp(operator,id);
        for(Runnable mutation:List.<Runnable>of(
            ()->publish.submit(id,null),()->publish.saveDraft(id,null),()->publish.deleteDraft(id,1L),
            ()->publish.createCustomTopic(id,null),()->publish.vote(id,1L,1L),
            ()->comments.create(1L,id,null),()->comments.react(1L,1L,id,"like"),()->comments.delete(1L,1L,id),
            ()->media.uploadImage(id,null),()->media.uploadVideo(id,null),()->media.cover(id,1L,null),()->media.retry(id,1L),
            ()->interactions.toggleLike(id,1L),()->interactions.toggleFavorite(id,1L))) {
            assertEquals("8888",assertThrows(BizException.class,mutation::run).getCode());
        }
    }

    @Test void officialDeletionInvalidatesBothAudiencesAndKeepsLinkage() throws Exception {
        String name="off_"+marker; long id=official(name);
        var login=appAuth.loginByPassword(name,PASSWORD); long linked=Long.parseLong(login.userInfo().userId());
        String adminToken="Bearer "+jwt.createAccessToken(id,"admin",0,1);
        String adminRefresh=jwt.createRefreshToken(id,"admin",0,1);
        success(post("/admin-user/delete",superToken(),Map.of("id",id)));
        assertEquals(1,flag("admin_user","deleted",id));
        assertEquals(1,flag("app_user","deleted",linked));
        assertEquals(id,db.queryForObject("SELECT official_admin_id FROM app_user WHERE id=?",Long.class,linked));
        assertEquals("8888",get("/auth/getUserInfo",adminToken).path("code").asText());
        assertEquals("8888",post("/auth/refreshToken",null,Map.of("refreshToken",adminRefresh)).path("code").asText());
        assertEquals("8888",get("/app/user/me","Bearer "+login.token()).path("code").asText());
        assertEquals("8888",post("/app/auth/refreshToken",null,Map.of("refreshToken",login.refreshToken())).path("code").asText());
        failed(post("/auth/login",null,Map.of("userName",name,"password",PASSWORD)));
        failed(post("/admin-user/updateStatus",superToken(),Map.of("id",id,"status",0)));
        failed(post("/admin-user/resetPassword",superToken(),Map.of("id",id,"password",PASSWORD)));
        failed(post("/admin-user/updateAccount",superToken(),Map.of("id",id,"account","new_"+marker)));
        failed(get("/admin-user/detail?id="+id,superToken()));
        assertEquals(0,post("/admin-user/list",superToken(),Map.of("pageNum",1,"pageSize",10,"keyword",name)).at("/data/total").asInt());
        long fresh=official(name.toUpperCase(Locale.ROOT));
        long freshCommunity=Long.parseLong(appAuth.loginByPassword(name,PASSWORD).userInfo().userId());
        assertNotEquals(id,fresh); assertNotEquals(linked,freshCommunity);
        success(post("/app-user/delete",superToken(),Map.of("id",linked)));
        assertEquals(0,flag("admin_user","deleted",fresh));
        assertEquals(0,flag("app_user","deleted",freshCommunity));
        assertEquals(fresh,accounts.find(name).getOwnerId());
        assertNotNull(adminAuth.login(name,PASSWORD));
    }

    @Test void communityDeletionCascadesAndUnlinkedOfficialCanBeDeleted() throws Exception {
        long id=official("linked_"+marker);
        long linked=Long.parseLong(appAuth.loginByPassword("linked_"+marker,PASSWORD).userInfo().userId());
        success(post("/app-user/delete",superToken(),Map.of("id",linked)));
        assertEquals(1,flag("admin_user","deleted",id)); assertEquals(1,flag("app_user","deleted",linked));
        long unlinked=official("alone_"+marker);
        success(post("/admin-user/delete",superToken(),Map.of("id",unlinked)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM app_user WHERE official_admin_id=?",Integer.class,unlinked));
    }

    @Test void permissionsValidationAndSuperProtection() throws Exception {
        long id=app(qq(),null),reader=official("reader_"+marker);
        String readerToken="Bearer "+jwt.createAccessToken(reader,"admin",0,1);
        for(String route:List.of("/app-user/delete","/admin-user/delete")) {
            failed(post(route,null,Map.of("id",id)));
            failed(post(route,readerToken,Map.of("id",id)));
            failed(post(route,appToken(id),Map.of("id",id)));
            for(Object body:List.of(Map.of(),Map.of("id",0),Map.of("id",-1),Map.of("id",Long.MAX_VALUE)))
                failed(post(route,superToken(),body));
        }
        failed(post("/admin-user/delete",superToken(),Map.of("id",operator)));
        assertThrows(BizException.class,()->deletions.deleteApp(reader,id));
        assertThrows(BizException.class,()->deletions.deleteOfficial(reader,reader));
        assertEquals(0,flag("app_user","deleted",id)); assertEquals(0,flag("admin_user","deleted",operator));
    }

    @Test void historyCountersAndCachedAuthorAndReplyIdentitiesArePreservedAndAnonymized() throws Exception {
        long author=app(qq(),"hist_"+marker),viewer=app(qq(),null);
        db.update("UPDATE app_user SET avatar='/uploads/history-avatar.webp',signature='旧签名',level=5 WHERE id=?",author);
        long postId=insert("post",Map.of("author_id",author,"author_nickname","旧昵称快照","author_bio","旧简介","title","历史内容"+marker,
            "content","正文保留","status",1,"like_count",1,"favorite_count",1,"comment_count",2));
        long root=insert("comment",Map.of("post_id",postId,"author_id",author,"author_nickname","旧评论昵称","content","评论保留","content_type","text","like_count",1));
        long reply=insert("comment",Map.of("post_id",postId,"author_id",viewer,"author_nickname","回复者","content","回复保留","content_type","text",
            "parent_id",root,"reply_to_id",root,"reply_to_nickname","旧评论昵称"));
        db.update("INSERT INTO user_like(user_id,post_id) VALUES(?,?)",author,postId);
        db.update("INSERT INTO user_favorite(user_id,post_id) VALUES(?,?)",author,postId);
        db.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?),(?,?)",author,viewer,viewer,author);
        db.update("INSERT INTO comment_reaction(comment_id,user_id,reaction) VALUES(?,?,'like')",root,author);
        long poll=insert("post_poll",Map.of("post_id",postId,"question","问题","total_votes",1));
        long option=insert("post_poll_option",Map.of("poll_id",poll,"option_text","选项","vote_count",1,"sort_order",0));
        db.update("INSERT INTO post_poll_vote(poll_id,option_id,user_id) VALUES(?,?,?)",poll,option,author);
        posts.detail(postId,true); posts.detail(postId,false);
        var query=new PostQueryParam(); query.setKeyword(marker); posts.list(query,true); posts.list(query,false);
        assertEquals("原昵称",posts.detail(postId,true).getAuthorNickname());
        deletions.deleteApp(operator,author);
        assertNull(caches.getCache("post-detail").get("author-profile-v1:true:"+postId));
        for(boolean published:new boolean[]{true,false}) {
            var detail=posts.detail(postId,published);
            assertEquals("已注销用户",detail.getAuthorNickname());
            assertTrue(detail.getAuthorAvatar()==null||detail.getAuthorAvatar().isEmpty());
            assertEquals("",detail.getAuthorBio());
            assertEquals("正文保留",detail.getContent());
            assertEquals("已注销用户",posts.list(query,published).getRecords().stream().filter(p->p.getId().equals(Long.toString(postId))).findFirst().orElseThrow().getAuthorNickname());
        }
        var comments=get("/app/post/"+postId+"/comments",appToken(viewer));
        success(comments);
        assertEquals("已注销用户",comments.at("/data/records/0/authorNickname").asText());
        assertEquals("评论保留",comments.at("/data/records/0/content").asText());
        assertEquals("已注销用户",comments.at("/data/records/0/replies/0/replyToNickname").asText());
        for(String relation:List.of("followers","following")) {
            var rows=get("/app/user/"+relation,appToken(viewer)); success(rows);
            assertEquals("已注销用户",rows.at("/data/records/0/nickname").asText());
            assertEquals("",rows.at("/data/records/0/avatar").asText());
            assertEquals("",rows.at("/data/records/0/signature").asText());
        }
        assertThrows(BizException.class,()->interactions.toggleFollow(viewer,author));
        for(String table:List.of("user_like","user_favorite","comment_reaction","post_poll_vote"))
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,author),table);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM user_follow WHERE follower_id=? OR followee_id=?",Integer.class,author,author));
        assertEquals(1,flag("post","like_count",postId)); assertEquals(1,flag("post","favorite_count",postId));
        assertEquals(2,flag("post","comment_count",postId)); assertEquals(1,flag("post_poll","total_votes",poll));
        assertEquals(author,db.queryForObject("SELECT author_id FROM post WHERE id=?",Long.class,postId));
        assertEquals("旧评论昵称",db.queryForObject("SELECT author_nickname FROM comment WHERE id=?",String.class,root));
    }

    @Test void rollbackRestoresBothIdentitiesRegistryAndCaches() {
        String name="roll_"+marker; long id=official(name);
        long community=Long.parseLong(appAuth.loginByPassword(name,PASSWORD).userInfo().userId());
        caches.getCache("post-detail").put("rollback-marker","warm");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            deletions.deleteOfficial(operator,id);
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(0,flag("admin_user","deleted",id)); assertEquals(0,flag("app_user","deleted",community));
        assertEquals(0,flag("admin_user","token_version",id)); assertEquals(0,flag("app_user","token_version",community));
        assertEquals(id,accounts.find(name).getOwnerId());
        assertNotNull(caches.getCache("post-detail").get("rollback-marker"));
    }

    @Test void concurrentDeleteAndOfficialFirstLoginNeverLeaveAnActiveCommunity() throws Exception {
        String name="race_"+marker; long id=official(name);
        race(()->deletions.deleteOfficial(operator,id),()->appAuth.loginByPassword(name,PASSWORD));
        assertEquals(1,flag("admin_user","deleted",id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM app_user WHERE official_admin_id=? AND deleted=FALSE",Integer.class,id));
        assertNull(accounts.find(name));
    }

    @Test void concurrentDeleteRenameAndEnableNeverRestoreAnIdentityOrReserveItsName() throws Exception {
        String name="race_"+marker; long id=official(name);
        race(()->deletions.deleteOfficial(operator,id),()->accounts.updateOfficialAccount(operator,id,"after_"+marker));
        assertEquals(1,flag("admin_user","deleted",id));
        assertNull(accounts.find(name)); assertNull(accounts.find("after_"+marker));
        long app=app(qq(),"enable_"+marker); users.updateStatus(app,1);
        race(()->deletions.deleteApp(operator,app),()->users.updateStatus(app,0));
        assertEquals(1,flag("app_user","deleted",app)); assertEquals(1,flag("app_user","status",app));
        assertNull(accounts.find("enable_"+marker));
    }

    private void race(Runnable deletion,Runnable other) throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await(); deletion.run(); return true;});
            var b=pool.submit(()->{start.await(); try{other.run();}catch(BizException expected){} return true;});
            start.countDown(); assertTrue(a.get(20,TimeUnit.SECONDS)); assertTrue(b.get(20,TimeUnit.SECONDS));
        }
    }
}
