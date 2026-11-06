package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.post.dto.PostQueryParam;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Run only in the disposable database supplied by verify-user-accounts.py. */
@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB",matches="true")
@SpringBootTest(properties="ciyuantown.redis.enabled=false")
@AutoConfigureMockMvc
@Transactional
@Import(PostAuthorNicknameTests.Caches.class)
class PostAuthorNicknameTests {
    @TestConfiguration
    @EnableCaching
    static class Caches {
        @Bean CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("post-list", "post-detail");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired PostService posts;
    @Autowired CacheManager caches;
    @Autowired ObjectMapper json;
    long author, postId;
    String token;

    @BeforeEach void seed() {
        clearCaches();
        String qq="9"+String.format("%010d",java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000_000L));
        author=new SimpleJdbcInsert(jdbc).withTableName("app_user").usingGeneratedKeyColumns("id")
            .usingColumns("qq_number","email","nickname")
            .executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname","当前昵称")).longValue();
        postId=new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id")
            .usingColumns("author_id","author_nickname","title","status")
            .executeAndReturnKey(Map.of("author_id",author,"author_nickname","发布时旧昵称","title","改名回归帖子","status",1)).longValue();
        token="Bearer "+jwt.createAccessToken(author,"app",0);
    }
    @AfterEach void clearCaches() {
        for (String name:caches.getCacheNames()) caches.getCache(name).clear();
    }

    @Test void historicalPostsReadCurrentNicknameAcrossFeedsAndDetail() throws Exception {
        mvc.perform(get("/app/post/"+postId)).andExpect(jsonPath("$.data.authorNickname").value("当前昵称"));
        for(String endpoint:new String[]{"list","mine"}) assertFeedName(endpoint,token,"当前昵称");
        // Another user's followed and favorite feeds also use the same author mapping.
        long viewer=new SimpleJdbcInsert(jdbc).withTableName("app_user").usingGeneratedKeyColumns("id")
            .usingColumns("email","nickname").executeAndReturnKey(Map.of("email","viewer-"+author+"@test.local","nickname","旁观用户")).longValue();
        jdbc.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?)",viewer,author);
        jdbc.update("INSERT INTO user_favorite(user_id,post_id) VALUES(?,?)",viewer,postId);
        String viewerToken="Bearer "+jwt.createAccessToken(viewer,"app",0);
        for(String endpoint:new String[]{"following","favorites"}) assertFeedName(endpoint,viewerToken,"当前昵称");
        assertEquals("当前昵称",posts.detail(postId,false).getAuthorNickname());
        assertEquals("发布时旧昵称",jdbc.queryForObject("SELECT author_nickname FROM post WHERE id=?",String.class,postId));
    }

    @Test void renameEvictsWarmPublicAndAdminCaches() throws Exception {
        var query=new PostQueryParam(); query.setPageSize(100);
        for(boolean published:new boolean[]{true,false}) {
            posts.list(query,published);
            assertEquals("当前昵称",posts.detail(postId,published).getAuthorNickname());
        }
        assertNotNull(caches.getCache("post-detail").get("author-profile-v2:true:"+postId));
        mvc.perform(post("/app/user/updateNickname").header("Authorization",token)
            .contentType("application/json").content("{\"nickname\":\"修改后昵称\"}"))
            .andExpect(jsonPath("$.code").value("0000"))
            .andExpect(jsonPath("$.data.nickname").value("修改后昵称"));
        for(boolean published:new boolean[]{true,false}) {
            assertNull(caches.getCache("post-detail").get("author-profile-v2:"+published+":"+postId));
            assertEquals("修改后昵称",posts.detail(postId,published).getAuthorNickname());
            assertEquals("修改后昵称",posts.list(query,published).getRecords().stream()
                .filter(p->p.getId().equals(Long.toString(postId))).findFirst().orElseThrow().getAuthorNickname());
        }
        assertFeedName("mine",token,"修改后昵称");
    }

    @Test void legacyPostWithoutAuthorKeepsSnapshot() {
        long legacy=new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id")
            .usingColumns("author_nickname","title","status")
            .executeAndReturnKey(Map.of("author_nickname","历史作者","title","历史帖子","status",1)).longValue();
        assertEquals("历史作者",posts.detail(legacy,true).getAuthorNickname());
    }

    private void assertFeedName(String endpoint,String viewerToken,String expected) throws Exception {
        var result=mvc.perform(get("/app/post/"+endpoint+"?pageSize=100").header("Authorization",viewerToken))
            .andExpect(jsonPath("$.code").value("0000")).andReturn();
        var rows=json.readTree(result.getResponse().getContentAsString()).path("data").path("records");
        for(var row:rows) if(row.path("id").asText().equals(Long.toString(postId))) {
            assertEquals(expected,row.path("authorNickname").asText()); return;
        }
        fail("Fixture post missing from "+endpoint);
    }
}
