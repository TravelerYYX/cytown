package com.yyx.ciyuantown.common.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.appuser.service.UserActivityService;
import com.yyx.ciyuantown.business.sys.entity.AdminUser;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.exception.GlobalExceptionHandler;
import com.yyx.ciyuantown.common.interceptor.*;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LegalLinksTests {
    JdbcTemplate db;
    ObjectMapper json = new ObjectMapper();
    LegalLinksService service;
    MockMvc mvc;

    @BeforeEach void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:legallinks;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("DROP ALL OBJECTS");
        db.execute("CREATE TABLE system_config(config_key VARCHAR(100) PRIMARY KEY, config_value TEXT, remark VARCHAR(255))");
        service = new LegalLinksService(db, json);
        var jwt = mock(JwtUtil.class);
        var users = mock(AdminUserMapper.class);
        var redis = mock(RedisHotspotService.class);
        var admin = new AdminUser(); admin.setId(1L); admin.setStatus(0); admin.setTokenVersion(0);
        when(jwt.verifyAndGetUserId("admin", "access")).thenReturn(1L);
        when(jwt.verifyAndGetUserId("readonly", "access")).thenReturn(1L);
        when(jwt.tokenVersion(anyString())).thenReturn(0);
        when(jwt.userType("readonly")).thenReturn(1);
        when(users.selectById(1L)).thenReturn(admin);
        when(redis.integer(anyString())).thenReturn(Optional.of(0));
        mvc = MockMvcBuilders.standaloneSetup(new LegalLinksController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addMappedInterceptors(new String[]{"/global-settings/**"}, new AuthInterceptor(jwt, json, users, redis), new ReadOnlyInterceptor(jwt, json))
            .addMappedInterceptors(new String[]{"/app/**"}, new AppAuthInterceptor(jwt, mock(AppUserMapper.class), json, redis, mock(UserActivityService.class)))
            .build();
    }

    LegalLinksService.Settings configured() {
        return new LegalLinksService.Settings(new LegalLinksService.Link(" https://example.test/terms?a=1&b=2#条款 ", "browser"),
            new LegalLinksService.Link("https://example.test/privacy", "inapp"),
            new LegalLinksService.Link("http://example.test/collection", "browser"));
    }

    @Test void defaultsAreEmptyBrowserLinksAndPublicWithoutLogin() throws Exception {
        assertEquals(LegalLinksService.defaults(), service.get());
        mvc.perform(get("/app/legal-links")).andExpect(jsonPath("$.code").value("0000"))
            .andExpect(jsonPath("$.data.userAgreement.linkUrl").value(""))
            .andExpect(jsonPath("$.data.privacyPolicy.openMode").value("browser"))
            .andExpect(jsonPath("$.data.personalInfoCollection.openMode").value("browser"));
    }

    @Test void adminSavePersistsAllModesAndDoesNotChangeOtherSettings() throws Exception {
        db.update("INSERT INTO system_config VALUES('delete_resource_files_enabled','0','test')");
        mvc.perform(post("/global-settings/legal-links").header("Authorization", "Bearer admin")
            .contentType("application/json").content(json.writeValueAsString(configured())))
            .andExpect(jsonPath("$.code").value("0000"));
        var persisted = new LegalLinksService(db, json).get();
        assertEquals("https://example.test/terms?a=1&b=2#条款", persisted.userAgreement().linkUrl());
        assertEquals("inapp", persisted.privacyPolicy().openMode());
        assertEquals("http://example.test/collection", persisted.personalInfoCollection().linkUrl());
        mvc.perform(get("/app/legal-links")).andExpect(jsonPath("$.data.privacyPolicy.openMode").value("inapp"));
        assertEquals("0", db.queryForObject("SELECT config_value FROM system_config WHERE config_key='delete_resource_files_enabled'", String.class));
        service.save(LegalLinksService.defaults());
        assertEquals(LegalLinksService.defaults(), service.get());
    }

    @Test void invalidLinksAndModesRejectWholeSave() {
        var valid = service.save(configured());
        for (String url : new String[]{"javascript:alert(1)", "/pages/login/index", "https://", "https://bad host/path", "https://user:pass@example.test/", "https://example.test/" + "a".repeat(2048)}) {
            assertThrows(BizException.class, () -> service.save(new LegalLinksService.Settings(valid.userAgreement(),
                valid.privacyPolicy(), new LegalLinksService.Link(url, "browser"))));
            assertEquals(valid, service.get());
        }
        assertThrows(BizException.class, () -> service.save(new LegalLinksService.Settings(new LegalLinksService.Link("", "unknown"), valid.privacyPolicy(), valid.personalInfoCollection())));
        assertThrows(BizException.class, () -> service.save(new LegalLinksService.Settings(null, valid.privacyPolicy(), valid.personalInfoCollection())));
    }

    @Test void writeRequiresAdminAndReadOnlyCannotChangeLinks() throws Exception {
        String body = json.writeValueAsString(configured());
        mvc.perform(get("/global-settings/legal-links")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/global-settings/legal-links").contentType("application/json").content(body)).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/global-settings/legal-links").header("Authorization", "Bearer readonly")
            .contentType("application/json").content(body)).andExpect(jsonPath("$.msg").value("该账号为只读账号，无操作权限"));
        mvc.perform(get("/global-settings/legal-links").header("Authorization", "Bearer readonly")).andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(post("/app/legal-links").contentType("application/json").content(body)).andExpect(jsonPath("$.code").value("1000"));
        assertEquals(LegalLinksService.defaults(), service.get());
    }
}
