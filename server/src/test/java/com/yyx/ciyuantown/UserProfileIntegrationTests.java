package com.yyx.ciyuantown;

import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"ciyuantown.redis.enabled=false"}) @AutoConfigureMockMvc @Transactional
class UserProfileIntegrationTests {
    @Autowired org.mybatis.spring.SqlSessionTemplate session;
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired JwtUtil jwt;
    @MockitoBean RedisHotspotService redis;
    long author,other;
    long user(String name) {
        String qq="9"+String.format("%010d",java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000_000L));
        return new SimpleJdbcInsert(db).withTableName("app_user").usingGeneratedKeyColumns("id").usingColumns("qq_number","email","nickname")
            .executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname",name)).longValue();
    }
    long authorPost(long owner,int status,String name) {
        return new SimpleJdbcInsert(db).withTableName("post").usingGeneratedKeyColumns("id").usingColumns("author_id","author_nickname","title","status")
            .executeAndReturnKey(Map.of("author_id",owner,"author_nickname","snapshot","title",name,"status",status)).longValue();
    }
    @BeforeEach void setup(){author=user("主页用户");other=user("查看者");}
    @Test void profileIsPublicAndDoesNotExposeAccountFields() throws Exception {
        db.update("UPDATE app_user SET coin_balance=12345,signature='测试签名',gender=1,ip_province='江苏' WHERE id=?",author);
        mvc.perform(get("/app/user/"+author+"/profile")).andExpect(jsonPath("$.code").value("0000"))
            .andExpect(jsonPath("$.data.userId").value(""+author)).andExpect(jsonPath("$.data.nickname").value("主页用户"))
            .andExpect(jsonPath("$.data.coinBalance").value(12345)).andExpect(jsonPath("$.data.ipProvince").value("江苏"))
            .andExpect(jsonPath("$.data.following").value(false)).andExpect(jsonPath("$.data.qqNumber").doesNotExist())
            .andExpect(jsonPath("$.data.email").doesNotExist()).andExpect(jsonPath("$.data.password").doesNotExist())
            .andExpect(jsonPath("$.data.tokenVersion").doesNotExist()).andExpect(jsonPath("$.data.account").doesNotExist());
    }
    @Test void authorFeedIsPaginatedAndCannotExposeOtherAuthorsOrDrafts() throws Exception {
        authorPost(author,0,"pending");authorPost(author,2,"hidden");authorPost(other,1,"other");
        long first=authorPost(author,1,"first"),second=authorPost(author,1,"second");
        db.update("UPDATE post SET create_time='2026-10-30 10:00:00' WHERE id IN (?,?)",first,second);
        mvc.perform(get("/app/user/"+author+"/posts").param("pageSize","1").param("status","0"))
            .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.records[0].id").value(""+second));
        mvc.perform(get("/app/user/"+author+"/posts").param("pageSize","1").param("pageNum","2"))
            .andExpect(jsonPath("$.data.records[0].id").value(""+first)).andExpect(jsonPath("$.data.records[0].authorId").value(""+author));
        mvc.perform(get("/app/user/"+other+"/posts")).andExpect(jsonPath("$.data.records[*].title",contains("other")));
        mvc.perform(get("/app/user/"+author+"/posts").param("pageSize","101")).andExpect(jsonPath("$.code").value(not("0000")));
    }
    @Test void guestSearchFiltersBeforePaginationAndExposesOnlyPublicUsers() throws Exception {
        String term = "search_" + UUID.randomUUID().toString().substring(0, 8);
        long image = authorPost(author, 1, term + " image");
        long video = authorPost(author, 1, term + " video");
        long body = authorPost(author, 1, "matching body");
        authorPost(author, 0, term + " pending");
        authorPost(author, 2, term + " hidden");
        authorPost(other, 1, term.replace('_', 'X') + " literal wildcard check");
        db.update("UPDATE post SET mode='video' WHERE id=?", video);
        db.update("UPDATE post SET mode='post',content=? WHERE id=?", term, body);
        db.update("UPDATE app_user SET nickname=? WHERE id IN (?,?)", term, author, other);
        db.update("UPDATE app_user SET deleted=TRUE WHERE id=?", other);
        mvc.perform(get("/app/search/posts").param("keyword",term).param("pageSize","1"))
            .andExpect(jsonPath("$.code").value("0000")).andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.records",hasSize(1)));
        mvc.perform(get("/app/search/posts").param("keyword",term).param("type","image"))
            .andExpect(jsonPath("$.data.records[*].id",contains(""+image)));
        mvc.perform(get("/app/search/posts").param("keyword",term).param("type","video"))
            .andExpect(jsonPath("$.data.records[*].id",contains(""+video)));
        mvc.perform(get("/app/search/posts").param("keyword",term).param("type","post"))
            .andExpect(jsonPath("$.data.records[*].id",contains(""+body)));
        mvc.perform(get("/app/search/posts").param("keyword",term).param("type","official"))
            .andExpect(jsonPath("$.data.total").value(0));
        long officialAdmin = new SimpleJdbcInsert(db).withTableName("admin_user").usingGeneratedKeyColumns("id")
            .usingColumns("username","password","nickname","user_type")
            .executeAndReturnKey(Map.of("username",term,"password","unused","nickname","官方搜索测试","user_type",1)).longValue();
        db.update("UPDATE app_user SET official_admin_id=? WHERE id=?", officialAdmin, author);
        session.clearCache();
        mvc.perform(get("/app/search/posts").param("keyword",term).param("type","official"))
            .andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/app/search/suggestions").param("keyword",term))
            .andExpect(jsonPath("$.data",contains(term+" image",term+" video")));
        mvc.perform(get("/app/search/users").param("keyword",term))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(""+author))
            .andExpect(jsonPath("$.data.records[0].email").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].qqNumber").doesNotExist());
    }
    @Test void relationshipBelongsToViewerAndDeletedProfilesAreUnavailable() throws Exception {
        db.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?)",other,author);
        mvc.perform(get("/app/user/"+author+"/profile").header("Authorization","Bearer "+jwt.createAccessToken(other,"app")))
            .andExpect(jsonPath("$.data.following").value(true)).andExpect(jsonPath("$.data.statistics.followerCount").value(1));
        db.update("UPDATE app_user SET deleted=TRUE WHERE id=?",author);session.clearCache();
        mvc.perform(get("/app/user/"+author+"/profile")).andExpect(jsonPath("$.code").value(not("0000")));
        mvc.perform(get("/app/user/"+author+"/posts")).andExpect(jsonPath("$.code").value(not("0000")));
    }
    @Test void locationRequiresLoginAndIgnoresTargetUserInRequest() throws Exception {
        mvc.perform(post("/app/user/location/refresh")).andExpect(jsonPath("$.code").value("8888"));
        db.update("UPDATE app_user SET ip_province='上海',ip_located_at=? WHERE id=?",System.currentTimeMillis(),other);
        mvc.perform(post("/app/user/location/refresh").header("Authorization","Bearer "+jwt.createAccessToken(other,"app")).contentType("application/json").content("{\"userId\":\""+author+"\"}"))
            .andExpect(jsonPath("$.code").value("0000")).andExpect(jsonPath("$.data.province").value("上海"));
        mvc.perform(get("/app/user/"+author+"/profile")).andExpect(jsonPath("$.data.ipProvince").isEmpty());
    }
    @Test void directConversationApiIsIdempotentAndRestrictedToParticipants() throws Exception {
        String token="Bearer "+jwt.createAccessToken(other,"app");
        String payload="{\"peerId\":\""+author+"\"}";
        String result=mvc.perform(post("/app/chat/conversations").header("Authorization",token).contentType("application/json").content(payload))
            .andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse().getContentAsString();
        String id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result).path("data").path("id").asText();
        mvc.perform(post("/app/chat/conversations").header("Authorization",token).contentType("application/json").content(payload))
            .andExpect(jsonPath("$.data.id").value(id));
        mvc.perform(get("/app/chat/conversations/"+id).header("Authorization",token)).andExpect(jsonPath("$.data.peerId").value(""+author));
        mvc.perform(get("/app/chat/conversations").header("Authorization",token)).andExpect(jsonPath("$.data").isEmpty());
        long stranger=user("第三方");
        mvc.perform(get("/app/chat/conversations/"+id).header("Authorization","Bearer "+jwt.createAccessToken(stranger,"app")))
            .andExpect(jsonPath("$.code").value(not("0000")));
        mvc.perform(post("/app/chat/messages").header("Authorization",token).contentType("application/json")
            .content("{\"peerId\":\""+author+"\",\"clientId\":\"direct_profile_test\",\"kind\":\"text\",\"content\":\"你好\"}"))
            .andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(get("/app/chat/conversations").header("Authorization",token)).andExpect(jsonPath("$.data[0].id").value(id));
        org.junit.jupiter.api.Assertions.assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_follow WHERE follower_id=? AND followee_id=?",Integer.class,other,author));
    }
}
