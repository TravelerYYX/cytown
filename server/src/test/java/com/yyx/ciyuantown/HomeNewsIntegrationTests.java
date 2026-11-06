package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.homeconfig.dto.NewsConfigParam;
import com.yyx.ciyuantown.business.homeconfig.service.HomeConfigService;
import com.yyx.ciyuantown.business.homeconfig.mapper.SystemConfigMapper;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Only run against the migrated development MySQL. All test data is rolled back. */
@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @Transactional
class HomeNewsIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired HomeConfigService home;
    @Autowired PostService posts;
    @Autowired SystemConfigMapper configs;
    String token;

    @BeforeEach void prepare() {
        jdbc.update("UPDATE post SET home_news_enabled=FALSE");
        home.updateNews(new NewsConfigParam(true,2,"测试占位资讯"));
        token="Bearer "+jwt.createAccessToken(jdbc.queryForObject("SELECT MIN(id) FROM admin_user",Long.class));
    }
    long seed(String title, int status, boolean selected, String time) {
        return new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id")
            .usingColumns("author_nickname","title","status","home_news_enabled","create_time")
            .executeAndReturnKey(Map.of("author_nickname","轮播测试","title",title,"status",status,
                "home_news_enabled",selected,"create_time",time)).longValue();
    }
    @Test void explicitSelectionIncludesAllPostsAndSortsStably() {
        List<Long> selected=new ArrayList<>();
        for(int i=0;i<7;i++) selected.add(seed("轮播"+i,1,true,"2026-01-01 12:00:00"));
        long newest=seed("时间优先",1,true,"2026-01-02 12:00:00");
        seed("待审核",0,true,"2026-01-03 12:00:00");
        seed("下架",2,true,"2026-01-03 12:00:00");
        seed("未勾选",1,false,"2026-01-03 12:00:00");
        Collections.reverse(selected); selected.addFirst(newest);
        var news=home.appConfig().news();
        assertEquals(selected,news.items().stream().map(HomeConfigService.NewsItem::postId).toList());
        assertEquals("时间优先",news.items().getFirst().title());
        posts.updateStatus(newest,2);
        assertEquals(7,home.appConfig().news().items().size());
        posts.updateStatus(newest,1);
        assertEquals(newest,home.appConfig().news().items().getFirst().postId());
        posts.delete(newest);
        assertEquals(7,home.appConfig().news().items().size());
    }
    @Test void toggleIsAuthenticatedValidatedAndIncludedInViews() throws Exception {
        long id=seed("轮播开关测试",1,false,"2026-01-01 12:00:00");
        String body=json.writeValueAsString(Map.of("id",Long.toString(id),"homeNewsEnabled",true));
        mvc.perform(post("/post/updateHomeNews").contentType("application/json").content(body))
            .andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/post/updateHomeNews").header("Authorization",token).contentType("application/json").content(body))
            .andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(get("/post/"+id).header("Authorization",token))
            .andExpect(jsonPath("$.data.homeNewsEnabled").value(true));
        mvc.perform(post("/post/list").header("Authorization",token).contentType("application/json")
            .content("{\"keyword\":\"轮播开关测试\"}"))
            .andExpect(jsonPath("$.data.records[0].homeNewsEnabled").value(true));
        for(String invalid:List.of("{}","{\"id\":0,\"homeNewsEnabled\":true}","{\"id\":"+id+"}"))
            mvc.perform(post("/post/updateHomeNews").header("Authorization",token).contentType("application/json").content(invalid))
                .andExpect(jsonPath("$.code").value("1000"));
        mvc.perform(post("/post/updateHomeNews").header("Authorization",token).contentType("application/json")
            .content("{\"id\":9223372036854775807,\"homeNewsEnabled\":true}"))
            .andExpect(jsonPath("$.code").value("1000"));
        posts.updateHomeNews(id,false);
        assertFalse(posts.detail(id,false).getHomeNewsEnabled());
        assertTrue(home.appConfig().news().items().isEmpty());
    }
    @Test void emptyTextPersistsValidatesAndSurvivesOldClients() throws Exception {
        mvc.perform(get("/app/home/config"))
            .andExpect(jsonPath("$.data.news.enabled").value(true))
            .andExpect(jsonPath("$.data.news.items").isEmpty())
            .andExpect(jsonPath("$.data.news.emptyText").value("测试占位资讯"));
        for(String invalid:List.of("   ","字".repeat(101)))
            mvc.perform(post("/home/news/update").header("Authorization",token).contentType("application/json")
                .content(json.writeValueAsString(new NewsConfigParam(true,3,invalid))))
                .andExpect(jsonPath("$.code").value("1000"));
        String valid="字".repeat(100);
        mvc.perform(post("/home/news/update").header("Authorization",token).contentType("application/json")
            .content(json.writeValueAsString(new NewsConfigParam(true,3," "+valid+" "))))
            .andExpect(jsonPath("$.data.emptyText").value(valid));
        assertEquals(valid,home.newsConfig().emptyText());
        home.updateNews(new NewsConfigParam(false,4,null));
        assertFalse(home.appConfig().news().enabled());
        assertEquals(valid,home.appConfig().news().emptyText());
        configs.deleteById("news_empty_text");
        assertEquals(HomeConfigService.DEFAULT_EMPTY_TEXT,home.newsConfig().emptyText());
    }
}
