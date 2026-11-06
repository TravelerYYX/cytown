package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.business.appuser.dto.ExperienceAdjustmentParam;
import com.yyx.ciyuantown.business.level.*;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB", matches="true")
@SpringBootTest(properties={"ciyuantown.redis.enabled=false", "ciyuantown.upload-dir=./target/level-test-uploads"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE) @Transactional
class ExperienceAdjustmentTests {
    @Autowired ExperienceAdjustmentService adjustments;
    @Autowired LevelService levels;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    long operator, user, official, community;
    long insert(String table, Map<String,Object> values) {
        return new SimpleJdbcInsert(db).withTableName(table).usingGeneratedKeyColumns("id")
                .usingColumns(values.keySet().toArray(String[]::new)).executeAndReturnKey(values).longValue();
    }
    long admin(int type) { return insert("admin_user", Map.of("username", "adjust_"+UUID.randomUUID().toString().substring(0,10), "password", "test-only", "nickname", "积分管理员", "user_type", type)); }
    @BeforeEach void seed() throws Exception {
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_"));
        db.update("UPDATE community_level_config SET version=0,config_json=? WHERE id=1", json.writeValueAsString(LevelConfig.defaults()));
        operator=admin(0); official=admin(1);
        user=insert("app_user",Map.of("email",UUID.randomUUID()+"@adjust.test","nickname","积分测试"));
        community=insert("app_user",Map.of("email",UUID.randomUUID()+"@adjust.test","nickname","官方测试","official_admin_id",official));
    }
    ExperienceAdjustmentParam input(long id, long delta) { return new ExperienceAdjustmentParam(id,delta,"  纠正积分  ",UUID.randomUUID().toString()); }
    ExperienceAdjustmentService.AdjustmentResult adjust(long delta) { return adjustments.adjust(operator,false,input(user,delta)); }
    long exp(long id) { return db.queryForObject("SELECT experience FROM app_user WHERE id=?",Long.class,id); }
    long ledger(long id) { return db.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=?",Long.class,id); }
    String token(long id,int type) { return "Bearer "+jwt.createAccessToken(id,"admin",0,type); }
    JsonNode postResult(String path, String token, Object body) throws Exception {
        var request=post(path).contentType("application/json").content(json.writeValueAsString(body));
        if(token!=null)request.header("Authorization",token);
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }
    @Test void increaseDeductAndZeroRecalculateLevelWithoutTierRewards() {
        long boundary=levels.config().levels().get(10).requiredExp();
        var increase=adjust(boundary);
        assertEquals(boundary,increase.afterExperience()); assertEquals(11,increase.afterLevel());
        assertEquals(1,ledger(user)); // No additional tier bonus.
        var decrease=adjust(-boundary);
        assertEquals(0,exp(user)); assertEquals(1,decrease.afterLevel()); assertEquals(2,ledger(user));
        assertEquals("纠正积分",db.queryForObject("SELECT reason FROM user_level_ledger WHERE id=?",String.class,Long.valueOf(decrease.id())));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=? AND action='tier_bonus'",Integer.class,user));
    }
    @Test void boundariesRejectInsteadOfClampingAndDoNotWriteLedger() {
        assertThrows(BizException.class,()->adjust(-1)); assertThrows(BizException.class,()->adjust(0));
        assertThrows(BizException.class,()->adjust(Long.MAX_VALUE)); assertThrows(BizException.class,()->adjust(Long.MIN_VALUE));
        assertEquals(LevelConfig.MAX_EXP,adjust(LevelConfig.MAX_EXP).afterExperience());
        assertThrows(BizException.class,()->adjust(1)); assertEquals(1,ledger(user));
        adjust(-LevelConfig.MAX_EXP); assertEquals(0,exp(user));
    }
    @Test void manualAmountsDoNotConsumeOrRestoreDailyRewardAllowance() {
        assertEquals(20,levels.award(user,"post","p1"));
        adjust(10); adjust(-10);
        assertEquals(20,levels.award(user,"post","p2")); assertEquals(20,levels.award(user,"post","p3"));
        assertEquals(0,levels.award(user,"post","p4")); assertEquals(60,exp(user));
    }
    @Test void replayReturnsOriginalResultEvenAfterBalanceAndConfigChange() {
        var request=input(user,100); var first=adjustments.adjust(operator,false,request);
        adjust(100);
        var c=levels.config();var items=new ArrayList<>(c.levels());items.set(1,new LevelConfig.Level(2,220,items.get(1).badgeUrl()));
        levels.save(new LevelConfig(c.version(),c.tiers(),items,c.rules()));
        assertEquals(first,adjustments.adjust(operator,false,request)); assertEquals(200,exp(user)); assertEquals(2,ledger(user));
        assertThrows(BizException.class,()->adjustments.adjust(operator,false,new ExperienceAdjustmentParam(user,101L,request.reason(),request.requestId())));
        assertThrows(BizException.class,()->adjustments.adjust(operator,false,new ExperienceAdjustmentParam(user,100L,"不同原因",request.requestId())));
        long other=admin(0);
        assertThrows(BizException.class,()->adjustments.adjust(other,false,request));
    }
    @Test void officialAndAppEntrypointsShareBalanceAndIdempotencyKey() {
        var request=input(official,123);
        var first=adjustments.adjust(operator,true,request);
        assertEquals(first,adjustments.adjust(operator,false,new ExperienceAdjustmentParam(community,123L,request.reason(),request.requestId())));
        assertEquals(123,exp(community)); assertEquals(1,ledger(community));
        assertThrows(BizException.class,()->adjustments.adjust(operator,true,input(admin(1),10)));
    }
    @Test void disabledAllowedAndDeletedTargetsRejectedFromBothEntrypoints() {
        db.update("UPDATE app_user SET status=1 WHERE id IN (?,?)",user,community);
        db.update("UPDATE admin_user SET status=1 WHERE id=?",official);
        adjust(10); adjustments.adjust(operator,true,input(official,10));
        db.update("UPDATE app_user SET deleted=1 WHERE id=?",user);
        assertThrows(BizException.class,()->adjust(10));
        db.update("UPDATE admin_user SET deleted=1 WHERE id=?",official);
        assertThrows(BizException.class,()->adjustments.adjust(operator,true,input(official,10)));
        assertThrows(BizException.class,()->adjustments.adjust(operator,false,input(community,10)));
        assertThrows(BizException.class,()->adjustments.adjust(operator,false,input(Long.MAX_VALUE,10)));
    }
    @Test void reasonAndRequestValidationAndServicePermissionChecks() {
        for(String reason:List.of("", " \t\n", "字".repeat(201)))
            assertThrows(BizException.class,()->adjustments.adjust(operator,false,new ExperienceAdjustmentParam(user,1L,reason,UUID.randomUUID().toString())));
        assertThrows(BizException.class,()->adjustments.adjust(operator,false,new ExperienceAdjustmentParam(user,1L,null,UUID.randomUUID().toString())));
        for(String id:List.of("", "1-1-1-1-1", "not-a-uuid"))
            assertThrows(BizException.class,()->adjustments.adjust(operator,false,new ExperienceAdjustmentParam(user,1L,"原因",id)));
        assertThrows(BizException.class,()->adjustments.adjust(null,false,input(user,1)));
        assertThrows(BizException.class,()->adjustments.adjust(official,false,input(user,1)));
        long disabledOperator=admin(0);
        db.update("UPDATE admin_user SET status=1 WHERE id=?",disabledOperator);
        assertThrows(BizException.class,()->adjustments.adjust(disabledOperator,false,input(user,1)));
        assertEquals(0,ledger(user));
        var unicodeRequest=new ExperienceAdjustmentParam(user,1L,"😀".repeat(200),UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        var unicodeResult=adjustments.adjust(operator,false,unicodeRequest);
        assertEquals(unicodeResult,adjustments.adjust(operator,false,new ExperienceAdjustmentParam(user,1L,unicodeRequest.reason(),unicodeRequest.requestId().toLowerCase(Locale.ROOT))));
    }
    @Test void endpointsEnforceAudienceReadOnlyAndStrictIntegerNumbers() throws Exception {
        var body=input(user,1);
        assertEquals("8888",postResult("/app-user/adjustExperience",null,body).path("code").asText());
        assertEquals("9999",postResult("/app-user/adjustExperience","Bearer "+jwt.createAccessToken(user,"app"),body).path("code").asText());
        for(String url:List.of("/app-user/adjustExperience","/admin-user/adjustExperience")) {
            assertEquals("1000",postResult(url,token(official,1),body).path("code").asText());
            for(Object invalid:List.of(1.5,1.0,"2",true)) {
                assertEquals("1000",postResult(url,token(operator,0),Map.of("id",user,"delta",invalid,"reason","测试","requestId",UUID.randomUUID().toString())).path("code").asText());
            }
        }
        assertEquals("0000",postResult("/app-user/adjustExperience",token(operator,0),input(user,100)).path("code").asText());
        assertEquals("0000",postResult("/admin-user/adjustExperience",token(operator,0),input(official,100)).path("code").asText());
        assertEquals(100,exp(user));assertEquals(100,exp(community));
    }
    @Test void historyPaginationPermissionsDetailsAndClientPrivacy() throws Exception {
        for(int i=0;i<23;i++)adjust(1);
        var first=adjustments.history(operator,false,user,1,20);
        assertEquals(23L,first.get("total"));assertEquals(2L,first.get("pages"));
        var records=(List<?>)first.get("records");assertEquals(20,records.size());
        var row=(Map<?,?>)records.getFirst();assertEquals("纠正积分",row.get("reason"));assertEquals(String.valueOf(operator),row.get("operatorId"));
        assertEquals(3,((List<?>)adjustments.history(operator,false,user,2,20).get("records")).size());
        assertThrows(BizException.class,()->adjustments.history(operator,false,user,0,20));
        assertThrows(BizException.class,()->adjustments.history(operator,false,user,1,101));
        assertThrows(BizException.class,()->adjustments.history(official,false,user,1,20));
        var detail=json.readTree(mvc.perform(get("/app-user/detail").param("id",String.valueOf(user)).header("Authorization",token(operator,0))).andReturn().getResponse().getContentAsString());
        assertEquals(23,detail.at("/data/experience").asInt());
        var history=json.readTree(mvc.perform(get("/app-user/experienceHistory").param("id",String.valueOf(user)).header("Authorization",token(operator,0))).andReturn().getResponse().getContentAsString());
        assertEquals(20,history.at("/data/records").size());
        assertTrue(history.at("/data/records/0/createTime").isTextual());
        adjustments.adjust(operator,true,input(official,10));
        var officialHistory=json.readTree(mvc.perform(get("/admin-user/experienceHistory").param("id",String.valueOf(official)).header("Authorization",token(operator,0))).andReturn().getResponse().getContentAsString());
        assertEquals(1,officialHistory.at("/data/total").asInt());assertEquals(10,officialHistory.at("/data/records/0/delta").asInt());
        var officialDetail=json.readTree(mvc.perform(get("/admin-user/detail").param("id",String.valueOf(official)).header("Authorization",token(operator,0))).andReturn().getResponse().getContentAsString());
        assertEquals(10,officialDetail.at("/data/community/experience").asInt());
        var denied=json.readTree(mvc.perform(get("/app-user/experienceHistory").param("id",String.valueOf(user)).header("Authorization",token(official,1))).andReturn().getResponse().getContentAsString());
        assertEquals("1000",denied.path("code").asText());
        var app=json.readTree(mvc.perform(get("/app/level/history").header("Authorization","Bearer "+jwt.createAccessToken(user,"app"))).andReturn().getResponse().getContentAsString());
        var clientRow=app.at("/data/records/0");assertEquals("admin_adjust",clientRow.path("action").asText());
        assertFalse(clientRow.has("reason"));assertFalse(clientRow.has("operatorId"));assertFalse(clientRow.has("operator_id"));
    }
}
