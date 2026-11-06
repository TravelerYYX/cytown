package com.yyx.ciyuantown;
import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Local migrated MySQL only. All authored data rolls back; no push service is called. */
@SpringBootTest(properties={"ciyuantown.jwt.secret=notice-api-integration-test-only-key-1234567890","ciyuantown.redis.enabled=false","ciyuantown.notice-push-delay=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE) @Transactional
class NoticeApiIntegrationTests {
 @Autowired org.mybatis.spring.SqlSessionTemplate sessions;
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired JwtUtil jwt;
 String admin,a,b;long userA,userB;
 @BeforeEach void setup(){
  Long adminId=db.queryForObject("SELECT MIN(id) FROM admin_user WHERE user_type=0 AND status=0 AND deleted=FALSE",Long.class);
  Integer version=db.queryForObject("SELECT COALESCE(token_version,0) FROM admin_user WHERE id=?",Integer.class,adminId);admin="Bearer "+jwt.createAccessToken(adminId,"admin",version,0);
  userA=seedUser();userB=seedUser();a="Bearer "+jwt.createAccessToken(userA,"app");b="Bearer "+jwt.createAccessToken(userB,"app");
 }
 long seedUser(){String qq="8"+String.format("%010d",java.util.concurrent.ThreadLocalRandom.current().nextLong(10000000000L));return new SimpleJdbcInsert(db).withTableName("app_user").usingGeneratedKeyColumns("id").usingColumns("qq_number","email","nickname","create_time").executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname","通知测试","create_time","2025-01-01 00:00:00")).longValue();}
 JsonNode post(String path,Object body,String token)throws Exception{var result=json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",token).contentType("application/json").content(json.writeValueAsString(body))).andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse().getContentAsString());sessions.clearCache();return result;}
 long category(String policy,List<Long> users)throws Exception{return post("/notice/category/create",Map.of("name","测试通知","recipientPolicy",policy,"recipientUserIds",users),admin).path("data").path("id").asLong();}
 long notice(long category,String type,List<Long> users)throws Exception{return post("/notice/create",Map.of("title","接收验证","categoryId",category,"audienceType",type,"recipientUserIds",users,"status",1,"blocks","[{\"type\":\"text\",\"text\":\"正文\"}]"),admin).path("data").asLong();}
 @Test void privateNoticeCannotLeakThroughDetailListOrSummaryAndReadStateIsShared()throws Exception{
  long c=category("choose",List.of()),id=notice(c,"users",List.of(userA));
  mvc.perform(get("/app/notice/"+id).header("Authorization",a)).andExpect(jsonPath("$.data.title").value("接收验证")).andExpect(jsonPath("$.data.recipientUserIds").doesNotExist());
  for(String token:List.of("",b,admin))mvc.perform(get("/app/notice/"+id).header("Authorization",token)).andExpect(jsonPath("$.code").value(token.equals(admin)?"9999":"1000"));
  mvc.perform(get("/app/notice/list").param("categoryId",Long.toString(c)).header("Authorization",b)).andExpect(jsonPath("$.data.total").value(0));
  String response=mvc.perform(get("/app/notice/list").param("categoryId",Long.toString(c)).header("Authorization",a)).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].unread").value(true)).andReturn().getResponse().getContentAsString();
  var ids=json.convertValue(json.readTree(response).path("data").path("readPublicationIds"),List.class);
  post("/app/notice/read/category",Map.of("categoryId",c,"publicationIds",ids),a);
  mvc.perform(get("/app/notice/list").param("categoryId",Long.toString(c)).header("Authorization",a)).andExpect(jsonPath("$.data.records[0].unread").value(false));
 }
 @Test void publishSnapshotSurvivesCategoryEditAndRepublishUsesNewRecipients()throws Exception{
  long c=category("users",List.of(userA)),id=notice(c,"all",List.of(userB));
  post("/notice/category/update",Map.of("id",c,"name","测试通知","recipientPolicy","users","recipientUserIds",List.of(userB)),admin);
  mvc.perform(get("/app/notice/"+id).header("Authorization",a)).andExpect(jsonPath("$.code").value("0000"));
  post("/notice/unpublish",Map.of("id",id),admin);post("/notice/publish",Map.of("id",id),admin);
  mvc.perform(get("/app/notice/"+id).header("Authorization",a)).andExpect(jsonPath("$.code").value("1000"));
  mvc.perform(get("/app/notice/"+id).header("Authorization",b)).andExpect(jsonPath("$.code").value("0000"));
 }
 @Test void publicHistoryAndAuthenticatedMutationsHaveCorrectAccess()throws Exception{
  long c=category("all",List.of()),id=notice(c,"users",List.of(userA));
  mvc.perform(get("/app/notice/"+id)).andExpect(jsonPath("$.code").value("0000"));
  mvc.perform(get("/app/notice/summary").header("Authorization","Bearer invalid")).andExpect(jsonPath("$.code").value("9999"));
  for(String path:List.of("/app/notice/read/category","/app/notice/read/one","/app/notice/device/bind","/notice/push/config"))mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content("{}")).andExpect(jsonPath("$.code").value("8888"));
  mvc.perform(get("/notice/push/config").header("Authorization",admin)).andExpect(jsonPath("$.data.secret").value(""));
 }
}
