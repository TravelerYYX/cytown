package com.yyx.ciyuantown;

import com.yyx.ciyuantown.business.homeconfig.dto.LayoutConfigParam;
import com.yyx.ciyuantown.business.homeconfig.service.HomeConfigService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Development database only: all writes roll back, and Redis caches are disabled. */
@SpringBootTest(properties="ciyuantown.redis.enabled=false")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Transactional
class HomeLayoutIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired HomeConfigService home;
    String token, readOnlyToken;

    @BeforeEach void prepare() {
        Long id=jdbc.queryForObject("SELECT MIN(id) FROM admin_user WHERE status=0",Long.class);
        Integer version=jdbc.queryForObject("SELECT COALESCE(token_version,0) FROM admin_user WHERE id=?",Integer.class,id);
        token="Bearer "+jwt.createAccessToken(id,"admin",version,0);
        readOnlyToken="Bearer "+jwt.createAccessToken(id,"admin",version,1);
        jdbc.update("DELETE FROM system_config WHERE config_key='home_default_feed_layout'");
    }
    @Test void missingAndInvalidStoredValuesUseSingle() throws Exception {
        mvc.perform(get("/app/home/config")).andExpect(jsonPath("$.data.defaultFeedLayout").value("single"));
        jdbc.update("INSERT INTO system_config(config_key,config_value,remark) VALUES ('home_default_feed_layout','unknown','test')");
        assertEquals("single",home.layoutConfig().defaultFeedLayout());
        assertEquals("single",home.appConfig().defaultFeedLayout());
    }
    @Test void savesBothModesAndRejectsInvalidInputWithoutChangingConfig() throws Exception {
        for(String mode:new String[]{"waterfall","single"}) {
            mvc.perform(post("/home/layout/update").header("Authorization",token).contentType("application/json")
                .content("{\"defaultFeedLayout\":\""+mode+"\"}"))
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.defaultFeedLayout").value(mode));
            mvc.perform(get("/home/layout/get").header("Authorization",token))
                .andExpect(jsonPath("$.data.defaultFeedLayout").value(mode));
            mvc.perform(get("/app/home/config")).andExpect(jsonPath("$.data.defaultFeedLayout").value(mode));
        }
        for(String body:new String[]{"{}","{\"defaultFeedLayout\":null}","{\"defaultFeedLayout\":\"\"}","{\"defaultFeedLayout\":\"grid\"}"}) {
            mvc.perform(post("/home/layout/update").header("Authorization",token).contentType("application/json").content(body))
                .andExpect(jsonPath("$.code").value("1000"));
            assertEquals("single",home.layoutConfig().defaultFeedLayout());
        }
    }
    @Test void requiresAdminAndRejectsReadOnlyWrites() throws Exception {
        String body="{\"defaultFeedLayout\":\"waterfall\"}";
        mvc.perform(get("/home/layout/get")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/home/layout/update").contentType("application/json").content(body))
            .andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get("/home/layout/get").header("Authorization",readOnlyToken))
            .andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(post("/home/layout/update").header("Authorization",readOnlyToken).contentType("application/json").content(body))
            .andExpect(jsonPath("$.code").value("1000"));
        assertEquals("single",home.layoutConfig().defaultFeedLayout());
        home.updateLayout(new LayoutConfigParam("waterfall"));
        assertEquals("waterfall",home.appConfig().defaultFeedLayout());
    }
}
