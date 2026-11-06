package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.business.homeconfig.service.HomeConfigService;
import com.yyx.ciyuantown.business.publish.service.PublishService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.cache.*;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Run only via the disposable database verifier, including real Spring cache interception. */
@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB",matches="true")
@SpringBootTest(properties={"ciyuantown.redis.enabled=false","ciyuantown.upload-dir=./target/channel-test-uploads"})
@AutoConfigureMockMvc @Transactional
@Import(ChannelPublishAccessTests.TestCache.class)
class ChannelPublishAccessTests {
    @TestConfiguration @EnableCaching
    static class TestCache {
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager(); }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtUtil jwt;
    @Autowired CacheManager caches;
    @Autowired HomeConfigService home;
    @Autowired org.mybatis.spring.SqlSessionTemplate session;
    long adminId,officialAdminId,ordinaryId,officialId,restrictedId,publicId;

    long insert(String table,Map<String,Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns("id")
            .usingColumns(values.keySet().toArray(String[]::new)).executeAndReturnKey(values).longValue();
    }
    @BeforeEach void setup() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_"));
        clearCaches();
        String suffix=UUID.randomUUID().toString().substring(0,8);
        adminId=insert("admin_user",Map.of("username","super_"+suffix,"password","unused","nickname","超管","user_type",0));
        officialAdminId=insert("admin_user",Map.of("username","official_"+suffix,"password","unused","nickname","官方","user_type",1));
        ordinaryId=insert("app_user",Map.of("email","ordinary_"+suffix+"@test.local","nickname","普通用户"));
        officialId=insert("app_user",Map.of("email","official_"+suffix+"@test.local","nickname","官方用户","official_admin_id",officialAdminId));
        jdbc.update("UPDATE home_channel SET enabled=FALSE,is_default=FALSE");
        restrictedId=insert("home_channel",Map.of("name","官方频道","sort_order",0,"enabled",true,"is_default",true,"admin_only",true));
        publicId=insert("home_channel",Map.of("name","交流频道","sort_order",1,"enabled",true,"is_default",false,"admin_only",false));
    }
    @AfterEach void clearCaches() { caches.getCacheNames().forEach(name->caches.getCache(name).clear()); }
    String appToken(long id) { return "Bearer "+jwt.createAccessToken(id,"app",0); }
    String adminToken() { return "Bearer "+jwt.createAccessToken(adminId,"admin",0,0); }
    JsonNode get(String url,long userId) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
            .header("Authorization",appToken(userId))).andReturn().getResponse().getContentAsString());
    }
    JsonNode post(String url,String token,Object body) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
            .header("Authorization",token).contentType("application/json").content(json.writeValueAsString(body)))
            .andReturn().getResponse().getContentAsString());
    }
    JsonNode config(long userId) throws Exception {
        var result=get("/app/publish/config",userId);assertEquals("0000",result.path("code").asText(),result.toString());return result.path("data");
    }
    boolean contains(JsonNode config,long id) {
        for(var channel:config.path("channels"))if(channel.path("id").asLong()==id)return true;
        return false;
    }
    Map<String,Object> submission(String mode,long owner,long channel) {
        List<Long> assets=new ArrayList<>();
        if(!mode.equals("post"))assets.add(insert("upload_asset",Map.of("owner_id",owner,"media_type",mode.equals("image")?"image":"video",
            "url","/uploads/channel-test.png","width",100,"height",100,"duration_ms",1000)));
        return Map.of("mode",mode,"channelId",channel,"title","权限测试","content","正文",
            "blocks",List.of(Map.of("type","paragraph","runs",List.of(Map.of("type","text","text","正文")))),
            "assetIds",assets,"topicIds",List.of(),"customTopics",List.of());
    }

    @Test void roleCachesAndRestrictedDefaultAreIsolated() throws Exception {
        // Warm official cache first; regular cache must not inherit its channel/default.
        assertTrue(contains(config(officialId),restrictedId));
        var ordinary=config(ordinaryId);assertFalse(contains(ordinary,restrictedId));assertEquals(publicId,ordinary.path("defaultChannelId").asLong());
        assertEquals(restrictedId,config(officialId).path("defaultChannelId").asLong());
        var cache=caches.getCache("publish-config");
        assertNotNull(cache.get("channel-access-v2:official"));assertNotNull(cache.get("channel-access-v2:regular"));
        assertNull(cache.get("app"));
        // Public homepage uses the unfiltered channel set.
        assertTrue(home.appConfig().channels().stream().anyMatch(c->c.id().equals(restrictedId)));
    }
    @Test void cachedOfficialAccessDoesNotSurviveDisabledOrChangedIdentity() throws Exception {
        assertTrue(contains(config(officialId),restrictedId));
        jdbc.update("UPDATE admin_user SET status=1 WHERE id=?",officialAdminId);
        session.clearCache(); // JDBC changes bypass MyBatis' transaction-local cache in this test.
        assertFalse(contains(config(officialId),restrictedId));
        assertEquals("该频道仅管理员可发布",post("/app/publish/submit",appToken(officialId),submission("post",officialId,restrictedId)).path("msg").asText());
        jdbc.update("UPDATE admin_user SET status=0,user_type=0 WHERE id=?",officialAdminId);
        session.clearCache();
        assertFalse(contains(config(officialId),restrictedId));
        jdbc.update("UPDATE app_user SET official_admin_id=NULL WHERE id=?",officialId);
        session.clearCache();
        assertFalse(contains(config(officialId),restrictedId));
        jdbc.update("UPDATE app_user SET official_admin_id=? WHERE id=?",Long.MAX_VALUE,officialId);
        session.clearCache();
        assertFalse(contains(config(officialId),restrictedId));
    }
    @ParameterizedTest @ValueSource(strings={"image","video","post"})
    void bypassIsRejectedWithoutWritesAndOfficialCanSubmit(String mode) throws Exception {
        var ordinary=submission(mode,ordinaryId,restrictedId);
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM post",Long.class);
        var denied=post("/app/publish/submit",appToken(ordinaryId),ordinary);
        assertEquals("1000",denied.path("code").asText());assertEquals("该频道仅管理员可发布",denied.path("msg").asText());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM post",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM upload_asset WHERE owner_id=? AND attached=TRUE",Integer.class,ordinaryId));
        var allowed=post("/app/publish/submit",appToken(officialId),submission(mode,officialId,restrictedId));
        assertEquals("0000",allowed.path("code").asText(),allowed.toString());
        assertEquals(restrictedId,jdbc.queryForObject("SELECT channel_id FROM post WHERE id=?",Long.class,allowed.at("/data/id").asLong()));
    }
    @Test void failedAttemptsDoNotConsumePublishQuota() throws Exception {
        var invalid=submission("post",ordinaryId,restrictedId);
        for(int i=0;i<12;i++) {
            var result=post("/app/publish/submit",appToken(ordinaryId),invalid);
            assertEquals("该频道仅管理员可发布",result.path("msg").asText());
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE author_id=?",Integer.class,ordinaryId));
        var allowed=post("/app/publish/submit",appToken(ordinaryId),submission("post",ordinaryId,publicId));
        assertEquals("0000",allowed.path("code").asText(),allowed.toString());
    }
    @Test void repostWithoutTextOrMediaCanBeSubmitted() throws Exception {
        jdbc.update("UPDATE publish_rule SET title_min=0,content_min=0 WHERE mode='post'");
        long sourceId=insert("post",Map.of("author_id",officialId,"author_nickname","官方用户","title","原帖","status",1));
        var repost=Map.of("mode","post","channelId",publicId,"blocks",List.of(),"assetIds",List.of(),"repostOfPostId",sourceId);

        var result=post("/app/publish/submit",appToken(ordinaryId),repost);

        assertEquals("0000",result.path("code").asText(),result.toString());
        assertEquals(sourceId,jdbc.queryForObject("SELECT repost_of_post_id FROM post WHERE id=?",Long.class,result.at("/data/id").asLong()));
    }
    @Test void textOnlyPostCanBeSubmittedButEmptyPostIsRejected() throws Exception {
        jdbc.update("UPDATE publish_rule SET title_min=0,content_min=0 WHERE mode='post'");
        var textOnly=submission("post",ordinaryId,publicId);
        var empty=new HashMap<>(textOnly);
        empty.put("blocks",List.of());
        empty.put("content","");

        var denied=post("/app/publish/submit",appToken(ordinaryId),empty);
        assertEquals("帖子至少需要正文或一个媒体块",denied.path("msg").asText());
        var result=post("/app/publish/submit",appToken(ordinaryId),textOnly);
        assertEquals("0000",result.path("code").asText(),result.toString());
        assertEquals("正文",jdbc.queryForObject("SELECT content FROM post WHERE id=?",String.class,result.at("/data/id").asLong()));
    }
    @Test void quotaCountsRecentSubmissionsIncludingPendingReviewAndExcludesOtherUsersAndOldPosts() throws Exception {
        for(int i=0;i<9;i++)insert("post",Map.of("author_id",ordinaryId,"author_nickname","测试用户","title","计数帖子","status",i%3));
        for(int i=0;i<10;i++)insert("post",Map.of("author_id",officialId,"author_nickname","其他用户","title","其他用户","status",0));
        long old=insert("post",Map.of("author_id",ordinaryId,"author_nickname","测试用户","title","历史帖子","status",1));
        jdbc.update("UPDATE post SET create_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE) WHERE id=?",old);
        var allowed=post("/app/publish/submit",appToken(ordinaryId),submission("post",ordinaryId,publicId));
        assertEquals("0000",allowed.path("code").asText(),allowed.toString());
        var denied=post("/app/publish/submit",appToken(ordinaryId),submission("post",ordinaryId,publicId));
        assertEquals("1000",denied.path("code").asText());
        assertTrue(denied.path("msg").asText().contains("10分钟内最多发布10条"));
        assertEquals(11,jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE author_id=?",Integer.class,ordinaryId));
    }
    @Test void noChannelsAndToggleOffInvalidateBothCaches() throws Exception {
        config(ordinaryId);config(officialId);
        var changed=post("/home/channel/update",adminToken(),Map.of("id",publicId,"name","交流频道","sortOrder",1,"enabled",true,"isDefault",false,"adminOnly",true));
        assertEquals("0000",changed.path("code").asText());
        var empty=config(ordinaryId);assertEquals(0,empty.path("channels").size());assertTrue(empty.path("defaultChannelId").isNull());
        changed=post("/home/channel/update",adminToken(),Map.of("id",restrictedId,"name","官方频道","sortOrder",0,"enabled",true,"isDefault",true,"adminOnly",false));
        assertEquals("0000",changed.path("code").asText());assertTrue(contains(config(ordinaryId),restrictedId));
        assertEquals("0000",post("/app/publish/submit",appToken(ordinaryId),submission("post",ordinaryId,restrictedId)).path("code").asText());
    }
    @Test void createUpdateAndQuickTogglePersistRestriction() throws Exception {
        var created=post("/home/channel/create",adminToken(),Map.of("name","新增频道","sortOrder",3,"enabled",true,"isDefault",false,"adminOnly",true));
        assertEquals("0000",created.path("code").asText());long id=created.at("/data/id").asLong();assertTrue(created.at("/data/adminOnly").asBoolean());
        var quick=post("/home/channel/update",adminToken(),Map.of("id",id,"name","新增频道","sortOrder",3,"enabled",false,"isDefault",false,"adminOnly",true));
        assertTrue(quick.at("/data/adminOnly").asBoolean());assertFalse(quick.at("/data/enabled").asBoolean());
        // Older clients omitting this field preserve it as well.
        var legacy=post("/home/channel/update",adminToken(),Map.of("id",id,"name","改名频道","sortOrder",3,"enabled",true,"isDefault",false));
        assertTrue(legacy.at("/data/adminOnly").asBoolean());
        assertTrue(jdbc.queryForObject("SELECT admin_only FROM home_channel WHERE id=?",Boolean.class,id));
        var defaultCreate=post("/home/channel/create",adminToken(),Map.of("name","默认开放","sortOrder",4));
        assertFalse(defaultCreate.at("/data/adminOnly").asBoolean());
    }
}
