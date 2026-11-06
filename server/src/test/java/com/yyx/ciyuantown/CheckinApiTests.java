package com.yyx.ciyuantown;
import com.yyx.ciyuantown.business.checkin.CheckinConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"ciyuantown.redis.enabled=false"}) @AutoConfigureMockMvc @Transactional
class CheckinApiTests {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired JwtUtil jwt;
 @BeforeEach void setup()throws Exception{db.update("UPDATE checkin_config SET version=1,config_json=? WHERE id=1",json.writeValueAsString(CheckinConfig.defaults()));}
 @Test void anonymousCanSeeCatalogButCannotClaimOrReadPrivateState()throws Exception{
  mvc.perform(get("/app/checkin/config")).andExpect(jsonPath("$.data.days.length()").value(31));
  mvc.perform(get("/app/checkin/frames")).andExpect(jsonPath("$.data[0].id").value("xingxi"));
  for(String route:List.of("me","history","frames/owned"))mvc.perform(get("/app/checkin/"+route)).andExpect(jsonPath("$.code").value("8888"));
  for(String route:List.of("sign","makeup"))mvc.perform(post("/app/checkin/"+route).contentType("application/json").content("{\"date\":\"2026-10-01\",\"version\":1}")).andExpect(jsonPath("$.code").value("8888"));
  mvc.perform(post("/checkin/config").contentType("application/json").content(json.writeValueAsString(CheckinConfig.defaults()))).andExpect(jsonPath("$.code").value("8888"));
 }
 @Test void authenticatedClaimAndHistoryUseAppIdentity()throws Exception{
  String qq="6"+String.format("%010d",java.util.concurrent.ThreadLocalRandom.current().nextLong(10000000000L));
  long user=new SimpleJdbcInsert(db).withTableName("app_user").usingGeneratedKeyColumns("id").usingColumns("qq_number","email","nickname").executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname","签到接口测试")).longValue();
  String token="Bearer "+jwt.createAccessToken(user,"app");
  mvc.perform(post("/app/checkin/sign").header("Authorization",token)).andExpect(jsonPath("$.code").value("0000")).andExpect(jsonPath("$.data.state.balance").value(10));
  mvc.perform(post("/app/level/sign-in").header("Authorization",token)).andExpect(jsonPath("$.data.gained").value(0));
  mvc.perform(get("/app/checkin/history").header("Authorization",token)).andExpect(jsonPath("$.data.total").value(1));
  mvc.perform(post("/app/checkin/makeup").header("Authorization",token).contentType("application/json").content("{\"date\":\"invalid\",\"version\":1}")).andExpect(jsonPath("$.code").value("1000"));
 }
}
