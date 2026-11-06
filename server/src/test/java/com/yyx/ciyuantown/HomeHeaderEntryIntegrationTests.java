package com.yyx.ciyuantown;

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

@SpringBootTest(properties="ciyuantown.redis.enabled=false")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Transactional
class HomeHeaderEntryIntegrationTests {
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
        jdbc.update("DELETE FROM system_config WHERE config_key LIKE 'home_header_%'");
    }
    String body(String type,String url,String mode,boolean enabled) {
        return "{\"name\":\" 顶部入口 \",\"iconUrl\":\" /static/test-header.png \",\"targetType\":\""+type+
            "\",\"linkUrl\":\""+url+"\",\"openMode\":\""+mode+"\",\"showTopbar\":false,\"enabled\":"+enabled+"}";
    }
    void save(String body,String code) throws Exception {
        mvc.perform(post("/home/header-entry/update").header("Authorization",token).contentType("application/json").content(body))
            .andExpect(jsonPath("$.code").value(code));
    }
    @Test void defaultsHiddenAndPersistsAllThreeTargetsIndependentlyOfQuickEntries() throws Exception {
        assertFalse(home.headerEntry().enabled()); assertNull(home.appConfig().headerEntry());
        int entries=home.entries(false).size();
        for(String mode:new String[]{"inapp","browser"}) {
            save(body("link","https://example.test/entry",mode,true),"0000");
            mvc.perform(get("/home/header-entry/get").header("Authorization",token))
                .andExpect(jsonPath("$.data.name").value("顶部入口"))
                .andExpect(jsonPath("$.data.iconUrl").value("/static/test-header.png"))
                .andExpect(jsonPath("$.data.openMode").value(mode));
            mvc.perform(get("/app/home/config"))
                .andExpect(jsonPath("$.data.headerEntry.openMode").value(mode))
                .andExpect(jsonPath("$.data.headerEntry.showTopbar").value(false));
        }
        save(body("app","/pages/settings/index","browser",true),"0000");
        assertEquals("inapp",home.appConfig().headerEntry().openMode());
        assertTrue(home.appConfig().headerEntry().showTopbar());
        assertEquals("/pages/settings/index",home.appConfig().headerEntry().linkUrl());
        save(body("app","/pages/settings/index","inapp",false),"0000");
        assertNull(home.appConfig().headerEntry());
        assertEquals("/pages/settings/index",home.headerEntry().linkUrl());
        assertEquals(entries,home.entries(false).size());
    }
    @Test void invalidConfigCannotOverwriteStoredEntry() throws Exception {
        save(body("app","/pages/settings/index","inapp",true),"0000");
        var valid=home.headerEntry();
        for(String invalid:new String[]{
            body("link","javascript:alert(1)","inapp",true),body("app","/pages/unknown/index","inapp",true),
            body("app","/pages/post/index?id=","inapp",true),body("link","https://example.test","invalid",true),
            body("wrong","https://example.test","inapp",true),body("link","https://example.test","inapp",true).replace("顶部入口"," "),
            body("link","https://example.test","inapp",true).replace("/static/test-header.png"," ")}) {
            save(invalid,"1000"); assertEquals(valid,home.headerEntry());
        }
    }
    @Test void adminAccessAndReadOnlyProtection() throws Exception {
        mvc.perform(get("/home/header-entry/get")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/home/header-entry/update").contentType("application/json").content(body("app","/pages/settings/index","inapp",true)))
            .andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get("/home/header-entry/get").header("Authorization",readOnlyToken)).andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(post("/home/header-entry/update").header("Authorization",readOnlyToken).contentType("application/json")
            .content(body("app","/pages/settings/index","inapp",true))).andExpect(jsonPath("$.code").value("1000"));
        assertFalse(home.headerEntry().enabled());
    }
}
