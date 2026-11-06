package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.business.homeconfig.EntryTarget;
import com.yyx.ciyuantown.common.storage.StorageService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real development MySQL round trips, with all entry writes rolled back. */
@SpringBootTest(properties = "ciyuantown.redis.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class EntryTargetIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @MockitoBean StorageService storage;
    String token;
    static final String ICON = "/uploads/test-entry-icon.svg";

    @BeforeEach void prepare() {
        Long id = jdbc.queryForObject("SELECT MIN(id) FROM admin_user WHERE status=0", Long.class);
        Integer version = jdbc.queryForObject("SELECT COALESCE(token_version,0) FROM admin_user WHERE id=?", Integer.class, id);
        token = "Bearer " + jwt.createAccessToken(id, "admin", version, 0);
        when(storage.find(ICON)).thenReturn(Optional.of(new StorageService.Stored("entry-test", "test", "config/mine-icons/test.svg", ICON, "image/svg+xml", 100)));
    }
    Map<String, Object> body() {
        return new HashMap<>(Map.of("name", "测试入口", "title", "测试入口", "description", "测试介绍", "iconUrl", ICON,
            "linkUrl", "https://example.com/entry?a=1&b=2", "openMode", "browser", "showTopbar", false, "sortOrder", 0, "enabled", true));
    }
    JsonNode send(String route, Map<String, Object> body, String code) throws Exception {
        return json.readTree(mvc.perform(post(route).header("Authorization", token).contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test void bothEntryGroupsPersistDestinationsAndSwitchBetweenLinkAndApp() throws Exception {
        for (String prefix : List.of("/home/entry", "/mine/entry")) {
            var body = body();
            JsonNode saved = send(prefix + "/create", body, "0000");
            assertEquals("link", saved.path("targetType").asText());
            assertEquals("browser", saved.path("openMode").asText());
            long id = saved.path("id").asLong(); body.put("id", id);
            for (String target : List.of("/pages/settings/index", "/pages/index/index?tab=message", "/pages/post/index?id=123&anchor=comments")) {
                body.put("targetType", "app"); body.put("linkUrl", target);
                saved = send(prefix + "/update", body, "0000");
                assertEquals("app", saved.path("targetType").asText());
                assertEquals(target, saved.path("linkUrl").asText());
                assertEquals("inapp", saved.path("openMode").asText());
                String table = prefix.startsWith("/home") ? "home_entry" : "mine_entry";
                assertEquals("app", jdbc.queryForObject("SELECT target_type FROM " + table + " WHERE id=?", String.class, id));
                String endpoint = prefix.startsWith("/home") ? "/app/home/config" : "/app/mine/entries";
                JsonNode publicData = json.readTree(mvc.perform(get(endpoint)).andReturn().getResponse().getContentAsString()).path("data");
                JsonNode entries = prefix.startsWith("/home") ? publicData.path("entries") : publicData;
                boolean found = false;
                for (JsonNode entry : entries) if (entry.path("id").asLong() == id) {
                    assertEquals("app", entry.path("targetType").asText());
                    assertEquals(target, entry.path("linkUrl").asText()); found = true;
                }
                assertTrue(found, "public app response must include the saved destination");
            }
            body.put("targetType", "link"); body.put("linkUrl", "https://example.com/return");
            body.put("openMode", "browser");
            saved = send(prefix + "/update", body, "0000");
            assertEquals("link", saved.path("targetType").asText());
            assertEquals("browser", saved.path("openMode").asText());
        }
    }

    @Test void bothEntryGroupsRejectInvalidOrIncompleteDestinations() throws Exception {
        for (String prefix : List.of("/home/entry", "/mine/entry")) {
            var body = body(); body.put("targetType", "app");
            for (String target : List.of("", "https://example.com", "//example.com", "/pages/unknown/index", "/pages/publish/cover",
                    "/pages/post/index?id=", "/pages/post/index?id=0", "/pages/post/index?id=-1", "/pages/post/index?id=9999999999999999999",
                    "/pages/post/index?id=1&id=2", "/pages/post/index?%69d=1", "/pages/notice/list", "/pages/notice/detail?id=abc",
                    "/pages/index/index?tab=bad", "/pages/settings/index#fragment")) {
                body.put("linkUrl", target); send(prefix + "/create", body, "1000");
            }
            body.put("targetType", "link"); body.put("linkUrl", "/pages/settings/index"); send(prefix + "/create", body, "1000");
            body.put("targetType", "other"); send(prefix + "/create", body, "1000");
        }
    }

    @Test void pageCatalogIsAvailableToAdminAndEveryCompletedOptionPassesValidation() throws Exception {
        mvc.perform(get("/home/entry/pages").header("Authorization", token)).andExpect(jsonPath("$.code").value("0000"))
            .andExpect(jsonPath("$.data[0].label").value("首页"));
        mvc.perform(get("/home/entry/pages")).andExpect(jsonPath("$.code").value("8888"));
        for (var page : EntryTarget.PAGES) {
            String route = page.value().endsWith("=") ? page.value() + "1" : page.value();
            assertEquals(route, EntryTarget.validate("app", route));
        }
    }

    @Test void browseHistoryIsSelectableAndCanBeDisabledWithItsDefaultIcon() throws Exception {
        String route = "/pages/browse-history/index";
        String icon = "/uploads/browse-history/mine-history.svg";
        mvc.perform(get("/home/entry/pages").header("Authorization", token))
            .andExpect(jsonPath("$.data[?(@.value == '"+route+"')].label").value(org.hamcrest.Matchers.hasItem("浏览历史")));
        mvc.perform(get(icon)).andExpect(status().isOk()).andExpect(content().contentType("image/svg+xml"));
        var body = body();
        body.put("targetType", "app"); body.put("linkUrl", route); body.put("iconUrl", icon);
        long id = send("/mine/entry/create", body, "0000").path("id").asLong();
        mvc.perform(get("/app/mine/entries")).andExpect(jsonPath("$.data[?(@.id == "+id+")]").isNotEmpty());
        body.put("id", id); body.put("enabled", false);
        send("/mine/entry/update", body, "0000");
        mvc.perform(get("/app/mine/entries")).andExpect(jsonPath("$.data[?(@.id == "+id+")]").isEmpty());
    }

    @Test void membershipEntryUsesDefaultAndPublishesTheConfiguredAppPage() throws Exception {
        String adminRoute = "/mine/entry/membership-target";
        String appRoute = "/app/mine/membership-target";
        jdbc.update("DELETE FROM system_config WHERE config_key='mine_membership_target'");
        mvc.perform(get(adminRoute)).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get(appRoute)).andExpect(jsonPath("$.data.target").value("/pages/membership/index"));

        for (String target : List.of("/pages/membership/subscription", "/pages/settings/index", "/pages/post/index?id=123")) {
            assertEquals(target, send(adminRoute, Map.of("target", target), "0000").path("target").asText());
            mvc.perform(get(appRoute)).andExpect(jsonPath("$.data.target").value(target));
        }
        send(adminRoute, Map.of("target", "https://example.com"), "1000");
        mvc.perform(get(appRoute)).andExpect(jsonPath("$.data.target").value("/pages/post/index?id=123"));
    }
}
