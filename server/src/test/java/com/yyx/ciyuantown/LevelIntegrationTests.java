package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.level.*;
import com.yyx.ciyuantown.business.interaction.service.InteractionService;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** All fixtures and experience mutations are rolled back against the local development database. */
@SpringBootTest(properties={"ciyuantown.redis.enabled=false","ciyuantown.upload-dir=./target/level-test-uploads"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE) @Transactional
class LevelIntegrationTests {
    @Autowired LevelService levels;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired InteractionService interactions;
    @Autowired PostService posts;
    long a,b,postId;
    LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    long user() {
        String qq="9"+String.format("%010d",java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000_000L));
        return new SimpleJdbcInsert(jdbc).withTableName("app_user").usingGeneratedKeyColumns("id").usingColumns("qq_number","email","nickname")
                .executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname","等级测试")).longValue();
    }
    @BeforeEach void seed() {
        try {jdbc.update("UPDATE community_level_config SET version=0,config_json=? WHERE id=1",json.writeValueAsString(LevelConfig.defaults()));}
        catch(Exception e){throw new IllegalStateException(e);}
        a=user();b=user();
        postId=new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id").usingColumns("author_id","author_nickname","title","status")
                .executeAndReturnKey(Map.of("author_id",a,"author_nickname","等级测试","title","等级奖励测试","status",1)).longValue();
    }
    String token(long user){return "Bearer "+jwt.createAccessToken(user,"app");}
    long exp(long user){return jdbc.queryForObject("SELECT experience FROM app_user WHERE id=?",Long.class,user);}
    @Test void defaultSixtyLevelsMatchTheDocument() {
        var c=LevelConfig.defaults();c.validate();assertEquals(60,c.levels().size());
        assertEquals(List.of("初旅","循迹","逐曜","织星","耀令","归穹"),c.tiers().stream().map(LevelConfig.Tier::title).toList());
        assertEquals(1,c.levelFor(0).level());assertEquals(2,c.levelFor(100).level());assertEquals(60,c.levelFor(Long.MAX_VALUE).level());
        for(var l:c.levels()){assertEquals(l.level(),c.levelFor(l.requiredExp()).level());if(l.level()>1)assertEquals(l.level()-1,c.levelFor(l.requiredExp()-1).level());}
    }
    @Test void signInIsIdempotentAndSevenDayStreakRepeatsWeekly() {
        for(int i=1;i<=6;i++)jdbc.update("INSERT INTO user_level_signin(user_id,sign_date,streak_days) VALUES(?,?,?)",a,today.minusDays(7-i),i);
        var first=levels.signIn(a);assertEquals(40,first.gained());assertEquals(7,first.progress().streakDays());assertTrue(first.progress().signedToday());
        assertEquals(0,levels.signIn(a).gained());assertEquals(40,exp(a));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=?",Integer.class,a));
        jdbc.update("DELETE FROM user_level_signin WHERE user_id=? AND sign_date=?",a,today);
        jdbc.update("DELETE FROM user_level_ledger WHERE user_id=?",a);
        jdbc.update("DELETE FROM user_coin_ledger WHERE user_id=?",a);
        jdbc.update("UPDATE user_level_signin SET streak_days=13 WHERE user_id=? AND sign_date=?",a,today.minusDays(1));
        assertEquals(40,levels.signIn(a).gained());assertEquals(14,levels.progress(a).streakDays());
    }
    @Test void brokenStreakResetsAndDatesUseShanghai() {
        jdbc.update("INSERT INTO user_level_signin(user_id,sign_date,streak_days) VALUES(?,?,6)",a,today.minusDays(2));
        assertEquals(1,levels.signIn(a).progress().streakDays());
        assertEquals(today,jdbc.queryForObject("SELECT MAX(sign_date) FROM user_level_signin WHERE user_id=?",java.sql.Date.class,a).toLocalDate());
        assertEquals(10,exp(a));
    }
    @Test void capPartialRewardDisabledRuleAndDuplicateSource() {
        var c=levels.config();var rules=new ArrayList<>(c.rules());
        rules.replaceAll(r->r.action().equals("comment")?new LevelConfig.Rule(r.action(),r.name(),3,5,true):r);
        levels.save(new LevelConfig(c.version(),c.tiers(),c.levels(),rules));
        assertEquals(3,levels.award(a,"comment","1"));assertEquals(0,levels.award(a,"comment","1"));assertEquals(2,levels.award(a,"comment","2"));
        assertEquals(0,levels.award(a,"comment","3"));assertEquals(5,exp(a));
        c=levels.config();rules=new ArrayList<>(c.rules());rules.replaceAll(r->r.action().equals("post")?new LevelConfig.Rule(r.action(),r.name(),20,60,false):r);
        levels.save(new LevelConfig(c.version(),c.tiers(),c.levels(),rules));assertEquals(0,levels.award(a,"post","disabled"));
    }
    @Test void promotionBonusIsGrantedExactlyOnceAndCanCascade() {
        var c=levels.config();long boundary=c.levels().get(10).requiredExp();
        jdbc.update("UPDATE app_user SET experience=?,level=10 WHERE id=?",boundary-2,a);
        assertEquals(52,levels.award(a,"comment","boundary"));assertEquals(11,levels.progress(a).level());
        jdbc.update("UPDATE app_user SET experience=?,level=10 WHERE id=?",boundary-2,a);
        assertEquals(2,levels.award(a,"comment","boundary-again"));
        c=levels.config();var tiers=new ArrayList<>(c.tiers());
        var tier=tiers.get(1);tiers.set(1,new LevelConfig.Tier(tier.id(),tier.minLevel(),tier.maxLevel(),tier.title(),tier.fullTitle(),tier.color(),tier.description(),100000));
        levels.save(new LevelConfig(c.version(),tiers,c.levels(),c.rules()));
        jdbc.update("UPDATE app_user SET experience=?,level=10 WHERE id=?",boundary-2,b);
        assertEquals(100202,levels.award(b,"comment","cascade"));assertEquals(60,levels.progress(b).level());
    }
    @Test void configurationRecalculatesLevelWithoutRetrospectiveBonusesAndRejectsStaleSaves() {
        jdbc.update("UPDATE app_user SET experience=150,level=2 WHERE id=?",a);
        var c=levels.config();var items=new ArrayList<>(c.levels());items.set(1,new LevelConfig.Level(2,200,items.get(1).badgeUrl()));
        var saved=levels.save(new LevelConfig(c.version(),c.tiers(),items,c.rules()));assertEquals(1,saved.version());
        assertEquals(1,jdbc.queryForObject("SELECT level FROM app_user WHERE id=?",Integer.class,a));assertEquals(150,exp(a));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=?",Integer.class,a));
        assertThrows(BizException.class,()->levels.save(c));
    }
    @Test void validatesRangesMonotonicThresholdsAndBadgeUrls() {
        var c=levels.config();var tiers=new ArrayList<>(c.tiers());var t=tiers.get(1);
        tiers.set(1,new LevelConfig.Tier(t.id(),12,20,t.title(),t.fullTitle(),t.color(),t.description(),50));
        assertThrows(BizException.class,()->new LevelConfig(0,tiers,c.levels(),c.rules()).validate());
        var items=new ArrayList<>(c.levels());items.set(1,new LevelConfig.Level(2,0,"/uploads/levels/level-2.png"));
        assertThrows(BizException.class,()->new LevelConfig(0,c.tiers(),items,c.rules()).validate());
        assertThrows(BizException.class,()->LevelConfig.validateBadge("javascript:alert(1)"));assertThrows(BizException.class,()->LevelConfig.validateBadge("/uploads/../secret"));
    }
    @Test void postAndCommentLikesNeverRewardSelfOrRepeatedToggles() throws Exception {
        interactions.toggleLike(a,postId);assertEquals(0,exp(a));
        interactions.toggleLike(b,postId);interactions.toggleLike(b,postId);interactions.toggleLike(b,postId);assertEquals(1,exp(a));
        String response=mvc.perform(post("/app/post/"+postId+"/comments").header("Authorization",token(a)).contentType("application/json").content("{\"content\":\"经验评论\"}"))
                .andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse().getContentAsString();
        String id=json.readTree(response).path("data").path("id").asText();assertEquals(3,exp(a));
        for(int i=0;i<3;i++)mvc.perform(post("/app/post/"+postId+"/comments/"+id+"/reaction").header("Authorization",token(b)).contentType("application/json").content("{\"reaction\":\"like\"}"))
                .andExpect(jsonPath("$.code").value("0000"));
        assertEquals(4,exp(a));
    }
    @Test void moderationAwardsOnlyFirstSuccessfulPublication() {
        jdbc.update("UPDATE post SET status=0 WHERE id=?",postId);assertEquals(0,exp(a));
        posts.updateStatus(postId,1);assertEquals(20,exp(a));posts.updateStatus(postId,2);posts.updateStatus(postId,1);assertEquals(20,exp(a));
    }
    @ParameterizedTest @ValueSource(strings={"image","video","post"})
    void allPublishModesRewardSuccessfulSubmission(String mode) throws Exception {
        jdbc.update("INSERT INTO system_config(config_key,config_value,remark) VALUES('publish_review_required','0','test') ON DUPLICATE KEY UPDATE config_value='0'");
        long channel=jdbc.queryForObject("SELECT MIN(id) FROM home_channel WHERE enabled=1",Long.class);
        List<Long> assets=new ArrayList<>();
        if(!mode.equals("post"))assets.add(new SimpleJdbcInsert(jdbc).withTableName("upload_asset").usingGeneratedKeyColumns("id")
                .usingColumns("owner_id","media_type","url","width","height","duration_ms")
                .executeAndReturnKey(Map.of("owner_id",a,"media_type",mode.equals("image")?"image":"video","url","/uploads/level-test.png","width",100,"height",100,"duration_ms",1000)).longValue());
        var body=Map.of("mode",mode,"channelId",channel,"title","等级发布测试","content","正文",
                "blocks",List.of(Map.of("type","paragraph","runs",List.of(Map.of("type","text","text","正文")))),"assetIds",assets,"topicIds",List.of(),"customTopics",List.of());
        mvc.perform(post("/app/publish/submit").header("Authorization",token(a)).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value("0000")).andExpect(jsonPath("$.data.status").value(1));
        assertEquals(20,exp(a));
    }
    @Test void shippedBadgeFallbackAndCustomPngKeepTransparency() throws Exception {
        byte[] badge;
        try(var input=getClass().getResourceAsStream("/static/uploads/levels/level-60.png")){assertNotNull(input);badge=input.readAllBytes();}
        mvc.perform(get("/uploads/levels/level-60.png")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        Long admin=jdbc.queryForObject("SELECT MIN(id) FROM admin_user WHERE status=0",Long.class);
        Integer version=jdbc.queryForObject("SELECT token_version FROM admin_user WHERE id=?",Integer.class,admin);
        String token="Bearer "+jwt.createAccessToken(admin,"admin",version,0);
        String response=mvc.perform(multipart("/level/badge").file(new org.springframework.mock.web.MockMultipartFile("file","badge.png","image/png",badge)).header("Authorization",token))
                .andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse().getContentAsString();
        var path=java.nio.file.Path.of("target/level-test-uploads").resolve(json.readTree(response).at("/data/url").asText().replace("/uploads/",""));
        try {
            var image=javax.imageio.ImageIO.read(path.toFile());assertTrue(image.getColorModel().hasAlpha());assertEquals(0,image.getRGB(0,0)>>>24);
        } finally {java.nio.file.Files.deleteIfExists(path);}
        mvc.perform(multipart("/level/badge").file(new org.springframework.mock.web.MockMultipartFile("file","invalid.png","image/png",new byte[]{1,2,3})).header("Authorization",token))
                .andExpect(jsonPath("$.code").value("1000"));
        mvc.perform(multipart("/level/badge").file(new org.springframework.mock.web.MockMultipartFile("file","badge.png","image/png",badge))
                .header("Authorization","Bearer "+jwt.createAccessToken(admin,"admin",version,1))).andExpect(jsonPath("$.code").value("1000"));
    }
    @Test void maximumLevelKeepsAccumulatingExperience() {
        long maximum=levels.config().levels().getLast().requiredExp();jdbc.update("UPDATE app_user SET experience=?,level=60 WHERE id=?",maximum,a);
        levels.signIn(a);assertEquals(maximum+10,exp(a));assertTrue(levels.progress(a).maxLevel());assertNull(levels.progress(a).nextLevelExp());
    }
    @Test void allSixtyBadgePngsHaveTransparentMarginsAndOpaqueCenters() throws Exception {
        for(int n=1;n<=60;n++)try(var input=getClass().getResourceAsStream("/static/uploads/levels/level-"+n+".png")) {
            assertNotNull(input,"badge "+n);var image=javax.imageio.ImageIO.read(input);
            assertEquals(192,image.getWidth());assertEquals(192,image.getHeight());assertTrue(image.getColorModel().hasAlpha());
            assertEquals(0,image.getRGB(0,0)>>>24);assertEquals(0,image.getRGB(191,191)>>>24);
            assertTrue((image.getRGB(96,96)>>>24)>240,"opaque badge center "+n);
        }
    }
    @Test void endpointsRequireCorrectAudienceAndReadOnlyAdminCannotWrite() throws Exception {
        mvc.perform(get("/app/level/config")).andExpect(jsonPath("$.data.levels.length()").value(60));
        mvc.perform(get("/app/level/me")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/app/level/sign-in")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get("/level/config").header("Authorization",token(a))).andExpect(jsonPath("$.code").value("9999"));
        Long admin=jdbc.queryForObject("SELECT MIN(id) FROM admin_user WHERE status=0",Long.class);
        Integer version=jdbc.queryForObject("SELECT token_version FROM admin_user WHERE id=?",Integer.class,admin);
        String readOnly="Bearer "+jwt.createAccessToken(admin,"admin",version,1);
        mvc.perform(post("/level/config").header("Authorization",readOnly).contentType("application/json").content(json.writeValueAsString(levels.config())))
                .andExpect(jsonPath("$.code").value("1000"));
        mvc.perform(post("/app/level/sign-in").header("Authorization",token(a))).andExpect(jsonPath("$.data.gained").value(10));
        mvc.perform(get("/app/level/history").header("Authorization",token(a))).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/app/level/history").header("Authorization",token(b))).andExpect(jsonPath("$.data.total").value(0));
    }
}
