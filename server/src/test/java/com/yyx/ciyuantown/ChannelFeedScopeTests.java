package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB",matches="true")
@SpringBootTest(properties={"ciyuantown.redis.enabled=false","ciyuantown.upload-dir=./target/channel-test-uploads"})
@AutoConfigureMockMvc @Transactional
@Import(ChannelPublishAccessTests.TestCache.class)
class ChannelFeedScopeTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtUtil jwt;
    @Autowired CacheManager caches;
    @Autowired org.mybatis.spring.SqlSessionTemplate session;
    long adminId,userId,aggregateId,categoryId,firstPost,secondPost;

    long insert(String table,Map<String,Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns("id")
            .usingColumns(values.keySet().toArray(String[]::new)).executeAndReturnKey(values).longValue();
    }
    @BeforeEach void setup() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_"));
        clearCaches();
        String suffix=UUID.randomUUID().toString().substring(0,8);
        adminId=insert("admin_user",Map.of("username","scope_"+suffix,"password","unused","nickname","超管","user_type",0));
        userId=insert("app_user",Map.of("email","scope_"+suffix+"@test.local","nickname","测试用户"));
        jdbc.update("UPDATE home_channel SET enabled=FALSE,is_default=FALSE");
        // Isolate the public feed so totals and visibility checks are deterministic.
        jdbc.update("UPDATE post SET status=2");
        aggregateId=insert("home_channel",Map.of("name","汇总频道","sort_order",0,"show_all_posts",true,"allow_publish",false));
        categoryId=insert("home_channel",Map.of("name","普通频道","sort_order",1,"is_default",true,"allow_publish",true));
        firstPost=insert("post",Map.of("author_id",userId,"author_nickname","测试用户","channel_id",categoryId,"title","类别帖子","status",1));
        secondPost=insert("post",Map.of("author_id",userId,"author_nickname","测试用户","channel_id",aggregateId,"title","历史汇总帖子","status",1));
        insert("post",Map.of("author_nickname","测试用户","channel_id",categoryId,"title","待审核帖子","status",0));
        insert("post",Map.of("author_nickname","测试用户","channel_id",aggregateId,"title","下架帖子","status",2));
    }
    @AfterEach void clearCaches() { caches.getCacheNames().forEach(name->caches.getCache(name).clear()); }
    String adminToken() { return "Bearer "+jwt.createAccessToken(adminId,"admin",0,0); }
    String appToken() { return "Bearer "+jwt.createAccessToken(userId,"app",0); }
    JsonNode get(String url,String token) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
            .header("Authorization",token)).andReturn().getResponse().getContentAsString());
    }
    JsonNode post(String url,Object body) throws Exception { return post(url,body,adminToken()); }
    JsonNode post(String url,Object body,String token) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
            .header("Authorization",token).contentType("application/json").content(json.writeValueAsString(body)))
            .andReturn().getResponse().getContentAsString());
    }
    Map<String,Object> settings(long id,boolean all,boolean publish) {
        return Map.of("id",id,"name",id==aggregateId?"可改名的频道":"普通频道","sortOrder",0,"enabled",true,
            "isDefault",id==categoryId,"showAllPosts",all,"allowPublish",publish);
    }
    JsonNode list(long id,int page) throws Exception {
        var result=get("/app/post/list?channelId="+id+"&pageNum="+page+"&pageSize=1",appToken());
        assertEquals("0000",result.path("code").asText(),result.toString());return result.path("data");
    }
    boolean contains(JsonNode config,long id) {
        for(var channel:config.path("channels"))if(channel.path("id").asLong()==id)return true;
        return false;
    }
    @Test void scopeAppliesToPublicFeedOnlyAndRetainsPaginationAndVisibility() throws Exception {
        var first=list(aggregateId,1);var second=list(aggregateId,2);
        assertEquals(2,first.path("total").asLong());assertEquals(2,first.path("pages").asLong());
        assertNotEquals(first.at("/records/0/id").asText(),second.at("/records/0/id").asText());
        assertEquals(1,first.at("/records/0/status").asInt());assertEquals(1,second.at("/records/0/status").asInt());
        assertEquals(1,list(categoryId,1).path("total").asLong());
        var admin=post("/post/list",Map.of("channelId",aggregateId,"status",1));
        assertEquals(1,admin.at("/data/total").asLong(),"Admin filtering must remain exact");
        var mine=get("/app/post/mine?channelId="+aggregateId,appToken());
        assertEquals(1,mine.at("/data/total").asLong(),"Mine filtering must remain exact");
        assertEquals(secondPost,mine.at("/data/records/0/id").asLong());
    }
    @Test void everyChannelCanToggleScopeAndInvalidatesCachedFeedAndHomeConfig() throws Exception {
        assertEquals(2,list(aggregateId,1).path("total").asLong());
        var home=get("/app/home/config",appToken()).path("data");
        assertTrue(home.at("/channels/0/showAllPosts").asBoolean());
        assertEquals("0000",post("/home/channel/update",settings(aggregateId,false,false)).path("code").asText());
        assertEquals(1,list(aggregateId,1).path("total").asLong());
        assertFalse(get("/app/home/config",appToken()).at("/data/channels/0/showAllPosts").asBoolean());
        assertEquals("0000",post("/home/channel/update",settings(categoryId,true,true)).path("code").asText());
        assertEquals(2,list(categoryId,1).path("total").asLong());
    }
    @Test void newChannelsDefaultToNoPublishingAndLegacyUpdatesPreserveSettings() throws Exception {
        var created=post("/home/channel/create",Map.of("name","新频道"));
        assertEquals("0000",created.path("code").asText());
        assertFalse(created.at("/data/allowPublish").asBoolean());assertFalse(created.at("/data/showAllPosts").asBoolean());
        var legacy=post("/home/channel/update",Map.of("id",aggregateId,"name","旧客户端编辑","enabled",false));
        assertTrue(legacy.at("/data/showAllPosts").asBoolean());assertFalse(legacy.at("/data/allowPublish").asBoolean());
        assertFalse(legacy.at("/data/enabled").asBoolean());
        assertFalse(get("/app/home/config",appToken()).path("data").path("channels").toString().contains("旧客户端编辑"));
        var invalid=post("/home/channel/update",settings(categoryId,false,false));
        assertEquals("1000",invalid.path("code").asText());assertEquals("默认发布频道必须允许发布",invalid.path("msg").asText());
    }
    @ParameterizedTest @ValueSource(strings={"image","video","post"})
    void noPublishingBlocksAllModesBeforeWritesAndConfigCanReenableIt(String mode) throws Exception {
        var config=get("/app/publish/config",appToken()).path("data");
        assertFalse(contains(config,aggregateId));assertTrue(contains(config,categoryId));
        var assets=new ArrayList<Long>();
        if(!mode.equals("post"))assets.add(insert("upload_asset",Map.of("owner_id",userId,"media_type",mode,"url","/uploads/scope.png","width",100,"height",100,"duration_ms",1000)));
        var body=Map.of("mode",mode,"channelId",aggregateId,"title","权限验证","content","正文",
            "blocks",List.of(Map.of("type","paragraph","runs",List.of(Map.of("type","text","text","正文")))),
            "assetIds",assets,"topicIds",List.of(),"customTopics",List.of());
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM post",Long.class);
        var denied=post("/app/publish/submit",body,appToken());
        assertEquals("1000",denied.path("code").asText());assertEquals("该频道不允许发布",denied.path("msg").asText());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM post",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM upload_asset WHERE owner_id=? AND attached=TRUE",Integer.class,userId));
        assertEquals("0000",post("/home/channel/update",settings(aggregateId,true,true)).path("code").asText());
        assertTrue(contains(get("/app/publish/config",appToken()).path("data"),aggregateId));
        assertEquals("0000",post("/app/publish/submit",body,appToken()).path("code").asText());
        assertEquals("0000",post("/home/channel/update",settings(aggregateId,true,false)).path("code").asText());
        assertFalse(contains(get("/app/publish/config",appToken()).path("data"),aggregateId));
    }
}
