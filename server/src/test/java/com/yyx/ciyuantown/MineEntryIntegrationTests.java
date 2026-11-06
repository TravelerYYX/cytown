package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.storage.StorageService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Development MySQL; database changes roll back and media storage is mocked (no uploaded artifacts). */
@SpringBootTest(properties = "ciyuantown.redis.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class MineEntryIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @MockitoBean StorageService storage;
    final Map<String, StorageService.Stored> objects = new HashMap<>();
    String token, readOnly, icon;
    static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path d='M0 0h24v24H0z'/></svg>";

    @BeforeEach void prepare() throws Exception {
        jdbc.update("DELETE FROM mine_entry");
        Long id = jdbc.queryForObject("SELECT MIN(id) FROM admin_user WHERE status=0", Long.class);
        Integer version = jdbc.queryForObject("SELECT COALESCE(token_version,0) FROM admin_user WHERE id=?", Integer.class, id);
        token = "Bearer " + jwt.createAccessToken(id, "admin", version, 0);
        readOnly = "Bearer " + jwt.createAccessToken(id, "admin", version, 1);
        objects.clear();
        when(storage.find(anyString())).thenAnswer(call -> Optional.ofNullable(objects.get(call.getArgument(0))));
        when(storage.put(any(), anyString(), any(), eq("image/svg+xml"))).thenAnswer(call -> {
            String key = call.getArgument(1), url = "/uploads/storage/test/" + key;
            byte[] content = Files.readAllBytes(call.getArgument(2));
            assertTrue(new String(content, StandardCharsets.UTF_8).contains("<svg"));
            var stored = new StorageService.Stored(UUID.randomUUID().toString(), "test", key, url, "image/svg+xml", content.length);
            objects.put(url, stored); return stored;
        });
        icon = uploadCode(SVG);
    }
    String uploadCode(String svg) throws Exception {
        String response = mvc.perform(post("/mine/entry/icon/code").header("Authorization", token)
                .contentType("application/json").content(json.writeValueAsString(Map.of("svg", svg))))
                .andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("url").asText();
    }
    Map<String, Object> entry(String title) {
        return new HashMap<>(Map.of("title", title, "description", "入口介绍", "iconUrl", icon, "linkUrl", "https://example.com/path?a=1&b=2",
                "openMode", "inapp", "showTopbar", true, "sortOrder", 0, "enabled", true));
    }
    long create(Map<String, Object> body) throws Exception {
        String response = send("create", body, token, "0000");
        return json.readTree(response).path("data").path("id").asLong();
    }
    String send(String action, Object body, String authorization, String code) throws Exception {
        return mvc.perform(post("/mine/entry/" + action).header("Authorization", authorization).contentType("application/json")
                .content(json.writeValueAsString(body))).andExpect(jsonPath("$.code").value(code)).andReturn().getResponse().getContentAsString();
    }
    @Test void publicListCrudFilteringAndStableSorting() throws Exception {
        mvc.perform(get("/app/mine/entries")).andExpect(jsonPath("$.data.length()").value(0));
        long first = create(entry("第一个")), second = create(entry("第二个"));
        mvc.perform(get("/app/mine/entries")).andExpect(jsonPath("$.data[0].id").value(first)).andExpect(jsonPath("$.data[1].id").value(second));
        send("sort", Map.of("ids", List.of(second, first)), token, "0000");
        mvc.perform(get("/app/mine/entries")).andExpect(jsonPath("$.data[0].id").value(second));
        var update = entry("修改后"); update.put("id", second); update.put("enabled", false); update.put("openMode", "browser"); update.put("showTopbar", false);
        send("update", update, token, "0000");
        mvc.perform(get("/app/mine/entries")).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(first));
        mvc.perform(get("/mine/entry/list").header("Authorization", token)).andExpect(jsonPath("$.data.length()").value(2));
        send("delete", Map.of("id", second), token, "0000"); send("delete", Map.of("id", first), token, "0000");
        mvc.perform(get("/app/mine/entries")).andExpect(jsonPath("$.data.length()").value(0));
    }
    @Test void rejectsIncompleteInvalidAndStaleWrites() throws Exception {
        for (var invalid : List.of(Map.of("title", " "), Map.of("title", "长".repeat(21)), Map.of("description", " "),
                Map.of("description", "长".repeat(101)), Map.of("iconUrl", "https://example.com/icon.svg"),
                Map.of("linkUrl", "javascript:alert(1)"), Map.of("linkUrl", "https://"), Map.of("openMode", "other"))) {
            var body = entry("测试"); body.putAll(invalid); send("create", body, token, "1000");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mine_entry", Integer.class));
        long id = create(entry("存在"));
        send("sort", Map.of("ids", List.of()), token, "1000");
        send("sort", Map.of("ids", List.of(id, id)), token, "1000");
        send("delete", Map.of("id", -1), token, "1000");
    }
    @Test void requiresAdminAndBlocksReadOnlyMutations() throws Exception {
        mvc.perform(get("/mine/entry/list")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get("/mine/entry/list").header("Authorization", readOnly)).andExpect(jsonPath("$.code").value("0000"));
        for (String action : List.of("create", "update", "delete", "sort", "icon/code")) {
            send(action, entry("测试"), "", "8888"); send(action, entry("测试"), readOnly, "1000");
        }
        mvc.perform(multipart("/mine/entry/icon/upload").file(new MockMultipartFile("file", "x.svg", "image/svg+xml", SVG.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", readOnly)).andExpect(jsonPath("$.code").value("1000"));
    }
    @Test void uploadsSvgFilesAndRejectsUnsafeCodeWithoutStoring() throws Exception {
        mvc.perform(multipart("/mine/entry/icon/upload").file(new MockMultipartFile("file", "icon.svg", "image/svg+xml", SVG.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", token)).andExpect(jsonPath("$.code").value("0000")).andExpect(jsonPath("$.data.url").isString());
        int before = objects.size();
        send("icon/code", Map.of("svg", "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>"), token, "1000");
        mvc.perform(multipart("/mine/entry/icon/upload").file(new MockMultipartFile("file", "icon.png", "image/png", SVG.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", token)).andExpect(jsonPath("$.code").value("1000"));
        assertEquals(before, objects.size());
    }
}
