package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.business.appuser.service.AppUserService;
import com.yyx.ciyuantown.business.post.dto.PostQueryParam;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB",matches="true")
@SpringBootTest(properties="ciyuantown.redis.enabled=false")
@AutoConfigureMockMvc @Transactional
@Import(PostAuthorNicknameTests.Caches.class)
class AvatarFrameTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired PostService posts;
    @Autowired AppUserService users;
    @Autowired CacheManager caches;
    long author, viewer, postId;
    String token;
    @BeforeEach void seed() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_"));
        for (String name:caches.getCacheNames()) caches.getCache(name).clear();
        author=insert("app_user",Map.of("nickname","头像框作者","email","frame-author@test.local"));
        viewer=insert("app_user",Map.of("nickname","另一个账号","email","frame-viewer@test.local"));
        postId=insert("post",Map.of("author_id",author,"author_nickname","头像框作者","title","头像框测试","status",1));
        insert("comment",Map.of("post_id",postId,"author_id",author,"author_nickname","头像框作者","content","测试评论","content_type","text"));
        jdbc.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?)",viewer,author);
        token="Bearer "+jwt.createAccessToken(author,"app",0);
    }
    long insert(String table, Map<String,Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns("id")
            .usingColumns(values.keySet().toArray(String[]::new)).executeAndReturnKey(values).longValue();
    }
    JsonNode getJson(String path,String bearer) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization",bearer)).andReturn().getResponse().getContentAsString());
    }
    JsonNode select(String frame) throws Exception {
        return json.readTree(mvc.perform(post("/app/user/updateAvatarFrame").header("Authorization",token)
            .contentType("application/json").content(json.writeValueAsString(Map.of("frameId",frame,"userId",viewer))))
            .andReturn().getResponse().getContentAsString());
    }
    @Test void selectionsPersistPerAccountAndRefreshEveryPublicSurface() throws Exception {
        for (String frame:new String[]{"star-sea","sakura-mist",""}) {
            posts.detail(postId,true); posts.list(new PostQueryParam(),true); // Warm caches before changing.
            assertEquals("0000",select(frame).path("code").asText());
            assertEquals(frame,jdbc.queryForObject("SELECT avatar_frame FROM app_user WHERE id=?",String.class,author));
            assertEquals("",jdbc.queryForObject("SELECT avatar_frame FROM app_user WHERE id=?",String.class,viewer));
            assertEquals(frame,users.me(author).avatarFrame());
            assertEquals(frame,getJson("/app/user/me",token).at("/data/avatarFrame").asText());
            String viewerToken="Bearer "+jwt.createAccessToken(viewer,"app",0);
            assertEquals(frame,getJson("/app/post/"+postId,viewerToken).at("/data/authorAvatarFrame").asText());
            assertEquals(frame,getJson("/app/post/"+postId+"/comments",viewerToken).at("/data/records/0/authorAvatarFrame").asText());
            assertEquals(frame,getJson("/app/user/following",viewerToken).at("/data/records/0/avatarFrame").asText());
            var row=posts.list(new PostQueryParam(),true).getRecords().stream().filter(p->p.getId().equals(""+postId)).findFirst().orElseThrow();
            assertEquals(frame,row.getAuthorAvatarFrame());
        }
    }
    @Test void invalidOrAnonymousChangesCannotWrite() throws Exception {
        assertNotEquals("0000",select("../../arbitrary.png").path("code").asText());
        assertNotEquals("0000",select("unknown").path("code").asText());
        var anonymous=mvc.perform(post("/app/user/updateAvatarFrame").contentType("application/json").content("{\"frameId\":\"star-sea\"}"))
            .andReturn().getResponse().getContentAsString();
        assertNotEquals("0000",json.readTree(anonymous).path("code").asText());
        var missing=mvc.perform(post("/app/user/updateAvatarFrame").header("Authorization",token).contentType("application/json").content("{}"))
            .andReturn().getResponse().getContentAsString();
        assertNotEquals("0000",json.readTree(missing).path("code").asText());
        assertEquals("",users.me(author).avatarFrame());
    }
    @Test void deletedAuthorsNeverExposeTheirFrame() throws Exception {
        select("star-sea");
        jdbc.update("UPDATE app_user SET deleted=TRUE WHERE id=?",author);
        for(String name:caches.getCacheNames()) caches.getCache(name).clear();
        assertEquals("",posts.detail(postId,true).getAuthorAvatarFrame());
        String viewerToken="Bearer "+jwt.createAccessToken(viewer,"app",0);
        assertEquals("",getJson("/app/post/"+postId+"/comments",viewerToken).at("/data/records/0/authorAvatarFrame").asText());
    }
}
