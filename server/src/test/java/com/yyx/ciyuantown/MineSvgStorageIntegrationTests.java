package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.storage.StorageService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real storage + database + public resource delivery. DB and uploaded files roll back after the test. */
@SpringBootTest(properties = "ciyuantown.redis.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class MineSvgStorageIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @MockitoSpyBean StorageService storage;

    @Test void bothInputsAreStoredAsSvgAndPubliclyReadableByConfiguredEntries() throws Exception {
        String id = UUID.randomUUID().toString();
        String directory = Path.of("target", "mine-svg-storage-tests", id).toAbsolutePath().toString();
        // Exercise the actual local provider, without changing the user's selected storage profile.
        doReturn(new StorageService.Config(id, "local", directory, "", "", "", "", "", "")).when(storage).active();
        Long adminId = jdbc.queryForObject("SELECT MIN(id) FROM admin_user WHERE status=0", Long.class);
        Integer version = jdbc.queryForObject("SELECT COALESCE(token_version,0) FROM admin_user WHERE id=?", Integer.class, adminId);
        String token = "Bearer " + jwt.createAccessToken(adminId, "admin", version, 0);
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path d='M0 0h24v24H0z' fill='#12bce2'/></svg>";
        List<String> responses = List.of(
            mvc.perform(post("/mine/entry/icon/code").header("Authorization", token).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("svg", svg))))
                    .andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse().getContentAsString(),
            mvc.perform(multipart("/mine/entry/icon/upload").file(new MockMultipartFile("file", "icon.svg", "image/svg+xml", svg.getBytes(StandardCharsets.UTF_8)))
                    .header("Authorization", token)).andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse().getContentAsString());
        for (String response : responses) {
            String url = json.readTree(response).path("data").path("url").asText();
            var object = storage.find(url).orElseThrow();
            assertEquals("image/svg+xml", object.contentType());
            mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/svg+xml"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("<path")));
            mvc.perform(post("/mine/entry/create").header("Authorization", token).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("title", "真实SVG", "description", "上传后可预览", "iconUrl", url,
                            "linkUrl", "https://example.com/", "openMode", "inapp", "showTopbar", true, "enabled", true, "sortOrder", 0))))
                    .andExpect(jsonPath("$.code").value("0000"));
        }
        mvc.perform(get("/app/mine/entries")).andExpect(jsonPath("$.data.length()").value(2));
    }
}
