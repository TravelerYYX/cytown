package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.interaction.service.InteractionService;
import com.yyx.ciyuantown.business.post.vo.PostListVO;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Local development MySQL only; each test rolls back all fixtures. */
@SpringBootTest(properties={"ciyuantown.redis.enabled=false"}) @AutoConfigureMockMvc @Transactional
class SocialInteractionTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired InteractionService interactions;
    @Autowired ObjectMapper json;
    @MockitoBean RedisHotspotService redis;
    long a, b, postA, postB;
    String tokenA, tokenB;
    long user(String name) {
        String qq = "9"+String.format("%010d",java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000_000L));
        return new SimpleJdbcInsert(jdbc).withTableName("app_user").usingGeneratedKeyColumns("id")
            .usingColumns("qq_number","email","nickname").executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname",name)).longValue();
    }
    long createPost(long author, int status) {
        return new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id")
            .usingColumns("author_id","author_nickname","title","status","like_count","favorite_count")
            .executeAndReturnKey(Map.of("author_id",author,"author_nickname","social","title","social interaction","status",status,"like_count",0,"favorite_count",0)).longValue();
    }
    @BeforeEach void setup() {
        a=user("Social A"); b=user("Social B"); postA=createPost(a,1); postB=createPost(b,1);
        tokenA="Bearer "+jwt.createAccessToken(a,"app"); tokenB="Bearer "+jwt.createAccessToken(b,"app");
        when(redis.member(anyString(),anyString())).thenReturn(Optional.of(false));
    }
    @Test void fourRelationshipStatesAndLists() throws Exception {
        mvc.perform(get("/app/post/"+postA).header("Authorization",tokenB))
            .andExpect(jsonPath("$.data.following").value(false)).andExpect(jsonPath("$.data.followedBy").value(false));
        assertTrue(interactions.toggleFollow(a,b).active());
        mvc.perform(get("/app/post/"+postB).header("Authorization",tokenA))
            .andExpect(jsonPath("$.data.following").value(true)).andExpect(jsonPath("$.data.followedBy").value(false));
        mvc.perform(get("/app/post/"+postA).header("Authorization",tokenB))
            .andExpect(jsonPath("$.data.following").value(false)).andExpect(jsonPath("$.data.followedBy").value(true));
        mvc.perform(get("/app/user/followers").header("Authorization",tokenB))
            .andExpect(jsonPath("$.data.records[0].userId").value(String.valueOf(a)))
            .andExpect(jsonPath("$.data.records[0].followedBy").value(true));
        mvc.perform(post("/app/interaction/toggleFollow").header("Authorization",tokenB).contentType("application/json").content("{\"userId\":"+a+"}"))
            .andExpect(jsonPath("$.data.active").value(true)).andExpect(jsonPath("$.data.followedBy").value(true));
        mvc.perform(get("/app/post/"+postA).header("Authorization",tokenB))
            .andExpect(jsonPath("$.data.following").value(true)).andExpect(jsonPath("$.data.followedBy").value(true));
        interactions.toggleFollow(a,b);
        mvc.perform(get("/app/interaction/followState").param("userId",String.valueOf(b)).header("Authorization",tokenA))
            .andExpect(jsonPath("$.data.active").value(false)).andExpect(jsonPath("$.data.followedBy").value(true));
        mvc.perform(get("/app/user/following").header("Authorization",tokenA)).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void databaseHydrationCopiesCachedObjectsAndIgnoresMissingRedisMembership() {
        interactions.toggleLike(a,postB); interactions.toggleFavorite(a,postB); interactions.toggleFollow(b,a);
        var cached=new PostListVO(); cached.setId(String.valueOf(postB)); cached.setAuthorId(String.valueOf(b));
        cached.setLikeCount(999); cached.setLiked(false); cached.setStatus(1);
        var viewerA=interactions.withState(List.of(cached),a).getFirst();
        var viewerB=interactions.withState(List.of(cached),b).getFirst();
        assertTrue(viewerA.getLiked()); assertTrue(viewerA.getFavorited()); assertTrue(viewerA.getFollowedBy());
        assertFalse(viewerB.getLiked()); assertFalse(viewerB.getFavorited()); assertEquals(1,viewerB.getLikeCount());
        assertEquals(999,cached.getLikeCount()); assertFalse(cached.getLiked()); assertFalse(cached.getFollowedBy());
        verify(redis,never()).member(anyString(),anyString());
    }
    @Test void togglesPersistExactCountersAndStats() throws Exception {
        interactions.toggleLike(a,postB); interactions.toggleFavorite(a,postB); interactions.toggleFollow(a,b);
        mvc.perform(get("/app/user/statistics").header("Authorization",tokenA))
            .andExpect(jsonPath("$.data.followingCount").value(1)).andExpect(jsonPath("$.data.favoriteCount").value(1));
        mvc.perform(get("/app/user/statistics").header("Authorization",tokenB))
            .andExpect(jsonPath("$.data.followerCount").value(1)).andExpect(jsonPath("$.data.receivedLikeCount").value(1));
        assertFalse(interactions.toggleLike(a,postB).active()); assertFalse(interactions.toggleFavorite(a,postB).active());
        assertEquals(0,jdbc.queryForObject("SELECT like_count FROM post WHERE id=?",Integer.class,postB));
        assertEquals(0,jdbc.queryForObject("SELECT favorite_count FROM post WHERE id=?",Integer.class,postB));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_like WHERE user_id=?",Integer.class,a));
    }
    @Test void favoritesAreOrderedPagedAndExcludeUnavailablePosts() throws Exception {
        long hidden=createPost(b,0), removed=createPost(b,2);
        interactions.toggleFavorite(a,postB); interactions.toggleFavorite(a,postA);
        jdbc.update("INSERT INTO user_favorite(user_id,post_id) VALUES(?,?),(?,?),(?,?)",a,hidden,a,removed,a,Long.MAX_VALUE);
        mvc.perform(get("/app/post/favorites").header("Authorization",tokenA).param("pageSize","1"))
            .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.records[0].id").value(String.valueOf(postA)))
            .andExpect(jsonPath("$.data.records[0].favorited").value(true));
        mvc.perform(get("/app/post/favorites").header("Authorization",tokenA).param("pageSize","1").param("pageNum","2"))
            .andExpect(jsonPath("$.data.records[0].id").value(String.valueOf(postB)));
        interactions.toggleFavorite(a,postA);
        mvc.perform(get("/app/post/favorites").header("Authorization",tokenA)).andExpect(jsonPath("$.data.total").value(1));
    }
    @Test void followingFeedReflectsRelationshipChanges() throws Exception {
        interactions.toggleFollow(a,b);
        mvc.perform(get("/app/post/following").header("Authorization",tokenA))
            .andExpect(jsonPath("$.data.records[0].id").value(String.valueOf(postB)))
            .andExpect(jsonPath("$.data.records[0].following").value(true));
        interactions.toggleFollow(a,b);
        mvc.perform(get("/app/post/following").header("Authorization",tokenA)).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void stalePublishedCacheCannotExposeUnpublishedOrDeletedPosts() {
        var cached=new PostListVO(); cached.setId(String.valueOf(postB)); cached.setAuthorId(String.valueOf(b)); cached.setStatus(1);
        jdbc.update("UPDATE post SET status=0 WHERE id=?",postB);
        assertTrue(interactions.withState(List.of(cached),a).isEmpty());
        cached.setStatus(0);
        assertEquals(1,interactions.withState(List.of(cached),b).size());
        jdbc.update("DELETE FROM post WHERE id=?",postB);
        assertTrue(interactions.withState(List.of(cached),a).isEmpty());
    }
    @Test void authAndInvalidTargetsCannotWrite() throws Exception {
        for(String path:List.of("/app/post/favorites","/app/user/statistics","/app/user/followers","/app/user/following"))
            mvc.perform(get(path)).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/app/interaction/toggleLike").contentType("application/json").content("{\"postId\":"+postB+"}"))
            .andExpect(jsonPath("$.code").value("8888"));
        assertThrows(Exception.class,()->interactions.toggleFollow(a,a));
        assertThrows(Exception.class,()->interactions.toggleLike(a,Long.MAX_VALUE));
        assertThrows(Exception.class,()->interactions.toggleFavorite(a,createPost(b,0)));
        jdbc.update("UPDATE app_user SET status=1 WHERE id=?",b);
        assertThrows(Exception.class,()->interactions.toggleFollow(a,b));
        mvc.perform(get("/app/user/followers").header("Authorization",tokenA).param("pageSize","101"))
            .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("0000")));
    }
}
