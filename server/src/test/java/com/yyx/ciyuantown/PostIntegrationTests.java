package com.yyx.ciyuantown;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 在已迁移的开发MySQL上测试；每个测试完整回滚，禁止指向生产库。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired PostService posts;
    @Autowired org.apache.ibatis.session.SqlSession sqlSession;
    @Autowired ObjectMapper json;
    long postId;
    String token;
    @BeforeEach void seed() {
        postId=new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id")
            .usingColumns("author_nickname","title","status")
            .executeAndReturnKey(Map.of("author_nickname","测试作者","title","P1集成测试唯一标题","status",1)).longValue();
        long root=new SimpleJdbcInsert(jdbc).withTableName("comment").usingGeneratedKeyColumns("id")
            .usingColumns("post_id","author_nickname","content")
            .executeAndReturnKey(Map.of("post_id",postId,"author_nickname","甲","content","根评论")).longValue();
        jdbc.update("INSERT INTO comment(post_id,parent_id,author_nickname,content) VALUES(?,?,?,?)",postId,root,"乙","一级回复");
        jdbc.update("INSERT INTO post_media(post_id,type,layout,aspect_ratio) VALUES(?,'image','wide',1.5)",postId);
        jdbc.update("INSERT INTO post_tag(post_id,name) VALUES(?,'测试标签')",postId);
        jdbc.update("UPDATE post SET comment_count=2 WHERE id=?",postId);
        Long admin=jdbc.queryForObject("SELECT MIN(id) FROM admin_user",Long.class);
        assertNotNull(admin,"开发库需要已迁移的管理员");
        token="Bearer "+jwt.createAccessToken(admin);
    }
    @Test void publicDetailAndComments() throws Exception {
        mvc.perform(get("/app/post/"+postId)).andExpect(jsonPath("$.code").value("0000"))
            .andExpect(jsonPath("$.data.id").value(Long.toString(postId)))
            .andExpect(jsonPath("$.data.media[0].aspectRatio").value(1.5))
            .andExpect(jsonPath("$.data.tags[0]").value("测试标签"))
            .andExpect(jsonPath("$.data.commentCount").value(2));
        mvc.perform(get("/app/post/"+postId+"/comments?pageNum=1&pageSize=1"))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].replyCount").value(1))
            .andExpect(jsonPath("$.data.records[0].replies[0].authorNickname").value("乙"));
        mvc.perform(get("/app/post/"+postId+"/comments?pageNum=2&pageSize=1"))
            .andExpect(jsonPath("$.data.records").isEmpty());
    }
    @Test void privateActionsAndVisibility() throws Exception {
        mvc.perform(post("/post/list").contentType("application/json").content("{}"))
            .andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/post/list").header("Authorization",token).contentType("application/json")
            .content("{\"keyword\":\"P1集成测试唯一标题\",\"status\":1}"))
            .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(post("/post/updateStatus").header("Authorization",token).contentType("application/json")
            .content("{\"id\":\""+postId+"\",\"status\":2}")).andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(get("/app/post/"+postId)).andExpect(jsonPath("$.msg").value("帖子不存在或已下架"));
        mvc.perform(get("/app/post/"+postId+"/comments")).andExpect(jsonPath("$.msg").value("帖子不存在或已下架"));
        mvc.perform(get("/post/"+postId).header("Authorization",token)).andExpect(jsonPath("$.data.status").value(2));
        mvc.perform(get("/app/post/list?pageSize=100")).andExpect(result -> {
            var rows=json.readTree(result.getResponse().getContentAsString()).path("data").path("records");
            for(var row:rows) assertNotEquals(Long.toString(postId),row.path("id").asText());
        });
    }
    @Test void invalidPagingAndStatusRejected() throws Exception {
        for(String query: List.of("pageNum=0","pageSize=0","pageSize=101","pageNum=-1")) {
            mvc.perform(get("/app/post/list?"+query)).andExpect(jsonPath("$.code").value("1000"));
        }
        mvc.perform(post("/post/updateStatus").header("Authorization",token).contentType("application/json")
            .content("{\"id\":\""+postId+"\",\"status\":3}")).andExpect(jsonPath("$.code").value("1000"));
        mvc.perform(get("/app/post/list?pageNum=2147483647&pageSize=100"))
            .andExpect(jsonPath("$.data.records").isEmpty());
    }
    @Test void deleteCascadesAndTransactionsRollback() throws Exception {
        mvc.perform(post("/post/delete").header("Authorization",token).contentType("application/json")
            .content("{\"id\":\""+postId+"\"}")).andExpect(jsonPath("$.code").value("0000"));
        for(String table:List.of("comment","post_media","post_tag"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE post_id=?",Integer.class,postId));
        assertNull(jdbc.query("SELECT id FROM post WHERE id=?",r -> r.next()?r.getLong(1):null,postId));
        // 跨帖子回复由复合外键拒绝。
        assertThrows(Exception.class,() -> jdbc.update("INSERT INTO comment(post_id,parent_id,author_nickname) VALUES(1,999999999,'非法')"));
    }
    @Test void followingFeedUsesRealPublishedPostsAndRequiresLogin() throws Exception {
        long follower=fixtureUser(),author=fixtureUser();
        jdbc.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?)",follower,author);jdbc.update("UPDATE post SET author_id=? WHERE id=?",author,postId);
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,status) VALUES(?,'测试作者','待审核不出现在关注动态',0)",author);
        String appToken="Bearer "+jwt.createAccessToken(follower,"app",0);
        mvc.perform(get("/app/post/following")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get("/app/post/following").header("Authorization",appToken)).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].id").value(Long.toString(postId)));
        jdbc.update("DELETE FROM user_follow WHERE follower_id=?",follower);sqlSession.clearCache();
        mvc.perform(get("/app/post/following").header("Authorization",appToken)).andExpect(jsonPath("$.data.records").isEmpty());
    }
    @Test void mineSearchRestrictsAuthorAndSearchesTitleAndContentBeforePaging() throws Exception {
        long owner=fixtureUser(),other=fixtureUser();
        String appToken="Bearer "+jwt.createAccessToken(owner,"app",0);
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,content,status) VALUES(?,'本人','星海作品','正文',1)",owner);
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,content,status) VALUES(?,'本人','待审核作品','正文中的星海',0)",owner);
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,content,status) VALUES(?,'本人','普通作品','其他内容',1)",owner);
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,content,status) VALUES(?,'本人','星海下架','星海',2)",owner);
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,content,status) VALUES(?,'他人','星海外人','星海',1)",other);
        mvc.perform(get("/app/post/mine-search").param("keyword","星海")).andExpect(jsonPath("$.code").value("8888"));
        for(int page=1;page<=2;page++) {
            mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("keyword","  星海  ")
                .param("authorId",Long.toString(other)).param("pageSize","1").param("pageNum",String.valueOf(page)))
                .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.pages").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].authorId").value(Long.toString(owner)));
        }
        mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("keyword","星海").param("pageNum","3").param("pageSize","1"))
            .andExpect(jsonPath("$.data.records").isEmpty());
        mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("keyword","   "))
            .andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void mineSearchTreatsSqlWildcardsLiterallyAndValidatesInput() throws Exception {
        long owner=fixtureUser();String appToken="Bearer "+jwt.createAccessToken(owner,"app",0);
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,content,status) VALUES(?,'本人','100%_!完成','搜索转义',1)",owner);
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,content,status) VALUES(?,'本人','100abc完成','普通内容',1)",owner);
        for(String keyword:List.of("%","_","!","100%_!"))
            mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("keyword",keyword))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].title").value("100%_!完成"));
        mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("keyword","不存在"))
            .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("keyword","字".repeat(101)))
            .andExpect(jsonPath("$.code").value("1000"));
        mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("pageNum","0"))
            .andExpect(jsonPath("$.code").value("1000"));
    }
    @Test void mineSearchFavoritesAndLikesUseViewerRelationsAndPublishedVisibility() throws Exception {
        long owner=fixtureUser(),other=fixtureUser();String appToken="Bearer "+jwt.createAccessToken(owner,"app",0);
        var ids=new ArrayList<Long>();
        for(int i=0;i<5;i++) {
            long id=new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id")
                .usingColumns("author_id","author_nickname","title","content","status")
                .executeAndReturnKey(Map.of("author_id",other,"author_nickname","其他作者","title","星海关系作品"+i,"content","正文","status",i==3?2:i==4?0:1)).longValue();
            ids.add(id);
        }
        // The viewer owns both relations for one work, but each scope returns one row.
        for(String table:List.of("user_favorite","user_like")) {
            for(int index:List.of(0,3,4))jdbc.update("INSERT INTO "+table+"(user_id,post_id) VALUES(?,?)",owner,ids.get(index));
            jdbc.update("INSERT INTO "+table+"(user_id,post_id) VALUES(?,?)",other,ids.get(1));
        }
        jdbc.update("INSERT INTO user_favorite(user_id,post_id) VALUES(?,?)",owner,ids.get(2));
        for(String scope:List.of("favorite","like")) {
            var response=mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("scope",scope).param("keyword","星海"))
                .andExpect(jsonPath("$.data.total").value(scope.equals("favorite")?2:1)).andReturn();
            var rows=json.readTree(response.getResponse().getContentAsString()).path("data").path("records");
            assertEquals(rows.size(),new HashSet<>(rows.findValuesAsText("id")).size());
            for(var row:rows) {
                assertEquals(1,row.path("status").asInt());assertEquals(other,row.path("authorId").asLong());
                assertNotEquals(ids.get(1).longValue(),row.path("id").asLong());
                assertTrue(row.path(scope.equals("favorite")?"favorited":"liked").asBoolean());
            }
        }
        mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("scope","publish").param("keyword","星海"))
            .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("scope","like").param("keyword"," "))
            .andExpect(jsonPath("$.data.records").isEmpty());
        mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("scope","invalid").param("keyword","星海"))
            .andExpect(jsonPath("$.code").value("1000"));
        jdbc.update("DELETE FROM user_like WHERE user_id=?",owner);sqlSession.clearCache();
        mvc.perform(get("/app/post/mine-search").header("Authorization",appToken).param("scope","like").param("keyword","星海"))
            .andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void likedPostsUseCurrentViewerAndPaginateInLikeOrder() throws Exception {
        long owner=fixtureUser(), other=fixtureUser();
        long recent=fixturePost("最近赞过",1), hidden=fixturePost("已下架",2), otherLike=fixturePost("他人赞过",1);
        String appToken="Bearer "+jwt.createAccessToken(owner,"app",0);
        jdbc.update("INSERT INTO user_like (user_id, post_id, create_time) VALUES (?, ?, '2026-01-01'), (?, ?, '2026-02-01'), (?, ?, '2026-03-01'), (?, ?, '2026-04-01')",
            owner, postId, owner, recent, owner, hidden, other, otherLike);
        mvc.perform(get("/app/post/likes")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get("/app/post/likes?pageNum=1&pageSize=1").header("Authorization",appToken))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(recent)))
            .andExpect(jsonPath("$.data.records[0].liked").value(true));
        mvc.perform(get("/app/post/likes?pageNum=2&pageSize=1").header("Authorization",appToken))
            .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(postId)));
        jdbc.update("DELETE FROM user_like WHERE user_id=? AND post_id=?",owner,recent);
        mvc.perform(get("/app/post/likes").header("Authorization",appToken))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(postId)));
    }
    @Test void viewHistoryIsPrivateDeduplicatedAndTracksPublishedVisibility() throws Exception {
        long owner=fixtureUser(), other=fixtureUser();
        long second=fixturePost("第二篇历史",1), hidden=fixturePost("已下架",2);
        String appToken="Bearer "+jwt.createAccessToken(owner,"app",0);
        String otherToken="Bearer "+jwt.createAccessToken(other,"app",0);
        mvc.perform(get("/app/post/history")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/app/post/{id}/view",postId)).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get("/app/post/{id}",postId).header("Authorization",appToken)).andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(get("/app/post/history").header("Authorization",appToken)).andExpect(jsonPath("$.data.records").isEmpty());
        for(long id:List.of(postId,second)) {
            mvc.perform(post("/app/post/{id}/view",id).header("Authorization",appToken).param("userId",Long.toString(other)))
                .andExpect(jsonPath("$.code").value("0000"));
        }
        jdbc.update("UPDATE user_post_history SET viewed_at=CASE WHEN post_id=? THEN '2026-01-01' ELSE '2026-01-02' END WHERE user_id=?",postId,owner);
        mvc.perform(get("/app/post/history?pageSize=1").header("Authorization",appToken))
            .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(second)));
        mvc.perform(post("/app/post/{id}/view",postId).header("Authorization",appToken)).andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(get("/app/post/history?pageSize=1").header("Authorization",appToken))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(postId)));
        mvc.perform(get("/app/post/history?pageNum=2&pageSize=1").header("Authorization",appToken))
            .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(second)));
        mvc.perform(get("/app/post/history").header("Authorization",otherToken)).andExpect(jsonPath("$.data.records").isEmpty());
        mvc.perform(post("/app/post/{id}/view",hidden).header("Authorization",appToken))
            .andExpect(jsonPath("$.msg").value("帖子不存在或已下架"));
        posts.updateStatus(postId,2);
        mvc.perform(get("/app/post/history").header("Authorization",appToken))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(second)));
        posts.delete(second);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_post_history WHERE post_id=?",Integer.class,second));
        mvc.perform(get("/app/post/history").header("Authorization",appToken)).andExpect(jsonPath("$.data.records").isEmpty());
    }
    private long fixturePost(String title,int status) {
        return new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id")
            .usingColumns("author_nickname","title","status")
            .executeAndReturnKey(Map.of("author_nickname","测试作者","title",title,"status",status)).longValue();
    }
    private long fixtureUser() {
        String qq="9"+String.format("%010d",java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000_000L));
        return new SimpleJdbcInsert(jdbc).withTableName("app_user").usingGeneratedKeyColumns("id")
            .usingColumns("qq_number","email","nickname").executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname","关注测试用户")).longValue();
    }
}
