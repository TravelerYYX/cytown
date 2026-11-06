package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"ciyuantown.redis.enabled=false","ciyuantown.upload-dir=./target/comment-test-uploads"})
@AutoConfigureMockMvc
@Transactional
class CommentIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired org.mybatis.spring.SqlSessionTemplate session;
    long ownerId;
    long commenterId;
    long outsiderId;
    long postId;

    @BeforeEach void seed() {
        ownerId = user("发布者"); commenterId = user("评论者"); outsiderId = user("无关用户");
        postId = new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id")
            .usingColumns("author_id","author_nickname","title","status","comment_count")
            .executeAndReturnKey(Map.of("author_id",ownerId,"author_nickname","发布者","title","评论功能测试","status",1,"comment_count",0)).longValue();
        jdbc.update("INSERT INTO system_config(config_key,config_value,remark) VALUES('comment_image_max','3','test') ON DUPLICATE KEY UPDATE config_value='3'");
        jdbc.update("INSERT INTO system_config(config_key,config_value,remark) VALUES('comment_delete_cascade','0','test') ON DUPLICATE KEY UPDATE config_value='0'");
    }

    @Test void createsSingleLevelRepliesAndAuthorFilter() throws Exception {
        String root = create(commenterId, Map.of("content","根评论","region","江苏")).path("data").path("id").asText();
        String ownerReply = create(ownerId, Map.of("parentId",root,"replyToId",root,"content","发布者回复")).path("data").path("id").asText();
        JsonNode nested = create(commenterId, Map.of("parentId",root,"replyToId",ownerReply,"content","回复发布者"));
        assertEquals(root,nested.at("/data/parentId").asText());
        assertEquals(ownerReply,nested.at("/data/replyToId").asText());
        assertEquals(3,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,postId));

        mvc.perform(get("/app/post/"+postId+"/comments?scope=author&sort=hot"))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].contextOnly").value(true))
            .andExpect(jsonPath("$.data.records[0].replyCount").value(1))
            .andExpect(jsonPath("$.data.records[0].replies[0].authorId").value(Long.toString(ownerId)));
        mvc.perform(get("/app/post/"+postId+"/comments/"+root+"/replies?scope=author"))
            .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test void rejectsAnonymousCrossPostAndInvalidNestedReplies() throws Exception {
        mvc.perform(post("/app/post/"+postId+"/comments").contentType("application/json").content("{\"content\":\"未登录\"}"))
            .andExpect(jsonPath("$.code").value("8888"));
        String root = create(commenterId,Map.of("content","当前帖子根评论")).at("/data/id").asText();
        long otherPost = new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id")
            .usingColumns("author_id","author_nickname","title","status","comment_count")
            .executeAndReturnKey(Map.of("author_id",ownerId,"author_nickname","发布者","title","另一个帖子","status",1,"comment_count",0)).longValue();
        mvc.perform(post("/app/post/"+otherPost+"/comments").header("Authorization",token(commenterId))
                .contentType("application/json").content(json.writeValueAsString(Map.of("parentId",root,"replyToId",root,"content","跨帖子回复"))))
            .andExpect(jsonPath("$.code").value("1000"));
        String reply = create(ownerId,Map.of("parentId",root,"replyToId",root,"content","一级回复")).at("/data/id").asText();
        mvc.perform(post("/app/post/"+postId+"/comments").header("Authorization",token(commenterId))
                .contentType("application/json").content(json.writeValueAsString(Map.of("parentId",reply,"replyToId",reply,"content","伪造嵌套"))))
            .andExpect(jsonPath("$.code").value("1000"));
    }

    @Test void standaloneEnabledEmojiCannotMixWithTextOrImages() throws Exception {
        Number groupId = new SimpleJdbcInsert(jdbc).withTableName("emoji_group").usingGeneratedKeyColumns("id")
            .usingColumns("name","sort_order","enabled").executeAndReturnKey(Map.of("name","评论测试表情组","sort_order",0,"enabled",true));
        Number emojiId = new SimpleJdbcInsert(jdbc).withTableName("publish_emoji").usingGeneratedKeyColumns("id")
            .usingColumns("group_id","name","image_url","sort_order","enabled")
            .executeAndReturnKey(Map.of("group_id",groupId.longValue(),"name","评论测试表情","image_url","/uploads/test-comment-emoji.png","sort_order",0,"enabled",true));
        mvc.perform(post("/app/post/"+postId+"/comments").header("Authorization",token(commenterId))
                .contentType("application/json").content(json.writeValueAsString(Map.of("emojiId",emojiId.longValue(),"content","不能混发"))))
            .andExpect(jsonPath("$.code").value("1000"));
        JsonNode created = create(commenterId,Map.of("emojiId",emojiId.longValue()));
        assertEquals("sticker",created.at("/data/contentType").asText());
        assertEquals("/uploads/test-comment-emoji.png",created.at("/data/stickerUrl").asText());
        jdbc.update("UPDATE publish_emoji SET enabled=0 WHERE id=?",emojiId.longValue());
        mvc.perform(post("/app/post/"+postId+"/comments").header("Authorization",token(commenterId))
                .contentType("application/json").content(json.writeValueAsString(Map.of("emojiId",emojiId.longValue()))))
            .andExpect(jsonPath("$.code").value("1000"));
    }

    @Test void likesToggleIndependentlyAndDislikesAreRejected() throws Exception {
        String id = create(commenterId,Map.of("content","互动测试")).path("data").path("id").asText();
        react(ownerId,id,"like").andExpect(jsonPath("$.data.reaction").value("like")).andExpect(jsonPath("$.data.likeCount").value(1));
        mvc.perform(post("/app/post/"+postId+"/comments/"+id+"/reaction").header("Authorization",token(ownerId))
            .contentType("application/json").content("{\"reaction\":\"dislike\"}"))
            .andExpect(jsonPath("$.code").value("1000"));
        react(ownerId,id,"like").andExpect(jsonPath("$.data.reaction").value(nullValue()))
            .andExpect(jsonPath("$.data.likeCount").value(0)).andExpect(jsonPath("$.data.dislikeCount").doesNotExist());
        assertEquals(0,jdbc.queryForObject("SELECT like_count FROM post WHERE id=?",Integer.class,postId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_like WHERE user_id=?",Integer.class,commenterId));
    }

    @Test void mineIsAuthenticatedPaginatedAndIncludesRepliesButNeverOtherAuthorsOrDeletedRows() throws Exception {
        mvc.perform(get("/app/comment/mine")).andExpect(jsonPath("$.code").value("8888"));
        String root=create(commenterId,Map.of("content","自己的评论")).at("/data/id").asText();
        create(ownerId,Map.of("content","他人的评论"));
        String reply=create(commenterId,Map.of("parentId",root,"replyToId",root,"content","自己的回复")).at("/data/id").asText();
        String deleted=create(commenterId,Map.of("content","已删除")).at("/data/id").asText();
        jdbc.update("UPDATE `comment` SET deleted=1 WHERE id=?",deleted);
        jdbc.update("UPDATE `comment` SET create_time='2026-11-06 08:00:00' WHERE id IN (?,?)",root,reply);
        mvc.perform(get("/app/comment/mine").header("Authorization",token(commenterId)).param("pageSize","1").param("userId",""+ownerId))
            .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.pages").value(2))
            .andExpect(jsonPath("$.data.records[0].id").value(reply)).andExpect(jsonPath("$.data.records[0].parentId").value(root))
            .andExpect(jsonPath("$.data.records[0].postTitle").value("评论功能测试"))
            .andExpect(jsonPath("$.data.records[0].postAvailable").value(true));
        mvc.perform(get("/app/comment/mine").header("Authorization",token(commenterId)).param("pageSize","1").param("pageNum","2"))
            .andExpect(jsonPath("$.data.records[0].id").value(root));
        mvc.perform(get("/app/comment/mine").header("Authorization",token(commenterId)).param("pageSize","1").param("pageNum","3"))
            .andExpect(jsonPath("$.data.records").isEmpty());
        for(String value:List.of("0","101"))mvc.perform(get("/app/comment/mine").header("Authorization",token(commenterId)).param("pageSize",value))
            .andExpect(jsonPath("$.code").value("1000"));
    }

    @Test void mineRetainsUnavailableSourcesAndUsesBodyForUntitledPosts() throws Exception {
        create(commenterId,Map.of("content","保留的记录","region","山东"));
        jdbc.update("UPDATE post SET title='',content='无标题正文',mode='image' WHERE id=?",postId);
        mvc.perform(get("/app/comment/mine").header("Authorization",token(commenterId)))
            .andExpect(jsonPath("$.data.records[0].postTitle").value("无标题正文"))
            .andExpect(jsonPath("$.data.records[0].postMode").value("image"))
            .andExpect(jsonPath("$.data.records[0].region").value("山东"));
        jdbc.update("UPDATE post SET status=2 WHERE id=?",postId);
        session.clearCache();
        mvc.perform(get("/app/comment/mine").header("Authorization",token(commenterId)))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].postAvailable").value(false))
            .andExpect(jsonPath("$.data.records[0].postTitle").value("帖子已下架"))
            .andExpect(jsonPath("$.data.records[0].content").value("保留的记录"));
    }

    @Test void legacyDislikesAreUnselectedAndDoNotAffectHotOrderOrLocate() throws Exception {
        String first=create(commenterId,Map.of("content","赞更多")).at("/data/id").asText();
        String second=create(ownerId,Map.of("content","赞更少")).at("/data/id").asText();
        jdbc.update("UPDATE `comment` SET like_count=5,dislike_count=99 WHERE id=?",first);
        jdbc.update("UPDATE `comment` SET like_count=4 WHERE id=?",second);
        jdbc.update("INSERT INTO comment_reaction(comment_id,user_id,reaction) VALUES(?,?,'dislike')",first,ownerId);
        session.clearCache();
        mvc.perform(get("/app/post/"+postId+"/comments").header("Authorization",token(ownerId)).param("pageSize","1"))
            .andExpect(jsonPath("$.data.records[0].id").value(first))
            .andExpect(jsonPath("$.data.records[0].viewerReaction").value(nullValue()))
            .andExpect(jsonPath("$.data.records[0].dislikeCount").doesNotExist());
        mvc.perform(get("/app/post/"+postId+"/comments/"+second+"/locate").param("pageSize","1"))
            .andExpect(jsonPath("$.data.rootPage").value(2)).andExpect(jsonPath("$.data.roots.records[0].id").value(second));
        react(ownerId,first,"like").andExpect(jsonPath("$.data.likeCount").value(6));
        react(ownerId,first,"like").andExpect(jsonPath("$.data.likeCount").value(5));
    }

    @Test void defaultDeleteTombstonesRootAndHardDeletesLeaves() throws Exception {
        String root = create(commenterId,Map.of("content","会被占位")).path("data").path("id").asText();
        String reply = create(ownerId,Map.of("parentId",root,"replyToId",root,"content","保留的回复")).path("data").path("id").asText();
        mvc.perform(post("/app/post/"+postId+"/comments/"+root+"/delete").header("Authorization",token(outsiderId)))
            .andExpect(jsonPath("$.code").value("1000"));
        mvc.perform(post("/app/post/"+postId+"/comments/"+root+"/delete").header("Authorization",token(commenterId)))
            .andExpect(jsonPath("$.data.deletedCount").value(1)).andExpect(jsonPath("$.data.tombstone").value(true));
        assertEquals(1,jdbc.queryForObject("SELECT deleted FROM comment WHERE id=?",Integer.class,Long.valueOf(root)));
        assertEquals(1,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,postId));
        mvc.perform(get("/app/post/"+postId+"/comments")).andExpect(jsonPath("$.data.records[0].deleted").value(true))
            .andExpect(jsonPath("$.data.records[0].authorNickname").value(""));
        mvc.perform(post("/app/post/"+postId+"/comments/"+reply+"/delete").header("Authorization",token(ownerId)))
            .andExpect(jsonPath("$.data.tombstone").value(false));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE id=?",Integer.class,Long.valueOf(reply)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE id=?",Integer.class,Long.valueOf(root)));
        assertEquals(0,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,postId));
    }

    @Test void cascadeDeleteRemovesThreadAndUpdatesCount() throws Exception {
        jdbc.update("UPDATE system_config SET config_value='1' WHERE config_key='comment_delete_cascade'");
        String root = create(commenterId,Map.of("content","整串删除")).path("data").path("id").asText();
        create(ownerId,Map.of("parentId",root,"replyToId",root,"content","回复一"));
        create(commenterId,Map.of("parentId",root,"replyToId",root,"content","回复二"));
        mvc.perform(post("/app/post/"+postId+"/comments/"+root+"/delete").header("Authorization",token(ownerId)))
            .andExpect(jsonPath("$.data.deletedCount").value(3)).andExpect(jsonPath("$.data.tombstone").value(false));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE post_id=?",Integer.class,postId));
        assertEquals(0,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,postId));
    }

    @Test void imageUploadAndLimitsAreEnforced() throws Exception {
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0Z8AAAAASUVORK5CYII=");
        List<Long> ids = new ArrayList<>();
        for (int i=0;i<2;i++) {
            String body = mvc.perform(multipart("/app/comment/assets/image")
                .file(new MockMultipartFile("file","comment.png","image/png",png)).header("Authorization",token(commenterId)))
                .andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse().getContentAsString();
            ids.add(json.readTree(body).at("/data/id").asLong());
        }
        jdbc.update("UPDATE system_config SET config_value='1' WHERE config_key='comment_image_max'");
        mvc.perform(post("/app/post/"+postId+"/comments").header("Authorization",token(commenterId)).contentType("application/json")
            .content(json.writeValueAsString(Map.of("assetIds",ids))))
            .andExpect(jsonPath("$.code").value("1000"));
        JsonNode created = create(commenterId,Map.of("content","配图","assetIds",List.of(ids.getFirst())));
        assertEquals(1,created.at("/data/media").size());
        assertEquals(1,jdbc.queryForObject("SELECT attached FROM upload_asset WHERE id=?",Integer.class,ids.getFirst()));

        String ownerBody = mvc.perform(multipart("/app/comment/assets/image")
                .file(new MockMultipartFile("file","owner.png","image/png",png)).header("Authorization",token(ownerId)))
            .andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse().getContentAsString();
        long ownerAssetId = json.readTree(ownerBody).at("/data/id").asLong();
        mvc.perform(post("/app/post/"+postId+"/comments").header("Authorization",token(commenterId)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("assetIds",List.of(ownerAssetId)))))
            .andExpect(jsonPath("$.code").value("1000"));
    }

    private long user(String nickname) {
        long suffix = ThreadLocalRandom.current().nextLong(100_000_000L,999_999_999L);
        String qq = "7"+suffix+ThreadLocalRandom.current().nextInt(10);
        return new SimpleJdbcInsert(jdbc).withTableName("app_user").usingGeneratedKeyColumns("id")
            .usingColumns("qq_number","email","nickname","status","token_version")
            .executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname",nickname,"status",0,"token_version",0)).longValue();
    }
    private String token(long userId) { return "Bearer "+jwt.createAccessToken(userId,"app"); }
    private JsonNode create(long userId,Map<String,?> data) throws Exception {
        String body = mvc.perform(post("/app/post/"+postId+"/comments").header("Authorization",token(userId))
            .contentType("application/json").content(json.writeValueAsString(data)))
            .andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
    private org.springframework.test.web.servlet.ResultActions react(long userId,String commentId,String reaction) throws Exception {
        return mvc.perform(post("/app/post/"+postId+"/comments/"+commentId+"/reaction")
            .header("Authorization",token(userId)).contentType("application/json").content("{\"reaction\":\""+reaction+"\"}"))
            .andExpect(jsonPath("$.code").value("0000"));
    }
}
