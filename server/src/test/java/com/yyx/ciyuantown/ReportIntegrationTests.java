package com.yyx.ciyuantown;

import com.yyx.ciyuantown.business.report.ReportService;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import com.yyx.ciyuantown.common.storage.ResourceCleanupService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"ciyuantown.redis.enabled=false"}) @AutoConfigureMockMvc @Transactional
class ReportIntegrationTests {
    @Autowired ReportService reports;@Autowired JdbcTemplate db;@Autowired MockMvc mvc;@Autowired JwtUtil jwt;@Autowired ResourceCleanupService cleanup;
    @MockitoBean RedisHotspotService redis;
    long a,b;String type,reason;
    long insert(String table,Map<String,Object> values){return new SimpleJdbcInsert(db).withTableName(table).usingColumns(values.keySet().toArray(String[]::new)).usingGeneratedKeyColumns("id").executeAndReturnKey(values).longValue();}
    long user(String name){String qq="9"+String.format("%010d",java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000_000L));return insert("app_user",Map.of("qq_number",qq,"email",qq+"@qq.com","nickname",name));}
    @BeforeEach void setup(){a=user("举报者");b=user("被举报者");type=reports.options().types().getFirst().id();reason=reports.options().reasons().getFirst().id();}
    ReportService.Submit param(String kind,long target,String text,List<String> images){return new ReportService.Submit(kind,""+target,type,reason,text,images);}
    long image(long owner,String media,String status){return insert("upload_asset",Map.of("owner_id",owner,"media_type",media,"url","/uploads/report-test-"+UUID.randomUUID()+".png","status",status));}
    @Test void storesEvidenceAndSnapshotAfterOptionsChangeAndDelete() {
        long asset=image(a,"image","ready");String report=reports.submit(a,param("user",b,"举报内容",List.of(""+asset)));
        var before=reports.detail(Long.parseLong(report));assertEquals("举报者",before.report().reporterName());assertEquals(""+b,before.report().reportedUserId());assertEquals(1,before.images().size());
        reports.saveOption(new ReportService.OptionParam(Long.valueOf(type),"type","更改后的类型",10),false);reports.deleteOption(Long.valueOf(reason));
        db.update("UPDATE app_user SET nickname='新昵称' WHERE id=?",b);
        var after=reports.detail(Long.parseLong(report));assertEquals(before.report().typeName(),after.report().typeName());assertEquals(before.report().reasonName(),after.report().reasonName());assertEquals("被举报者",after.report().reportedUserName());
        assertFalse(reports.options().reasons().stream().anyMatch(x->x.id().equals(reason)));
        // Account cleanup must not remove evidence already attached to a report.
        cleanup.retireAccount(a,false);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM upload_asset WHERE id=?",Integer.class,asset));
    }
    @Test void reportsPostAndResolvesAuthorOnServer() {
        long post=insert("post",Map.of("author_id",b,"author_nickname","原作者","title","被举报帖子","content","原文","status",1));
        String report=reports.submit(a,param("post",post,"帖子举报",List.of()));db.update("UPDATE post SET title='已编辑',content='修改后' WHERE id=?",post);
        var detail=reports.detail(Long.parseLong(report));assertEquals(""+b,detail.report().reportedUserId());assertTrue(detail.targetSnapshot().toString().contains("被举报帖子"));
        assertThrows(BizException.class,()->reports.submit(b,param("post",post,"自己",List.of())));
    }
    @Test void validatesInputOwnershipAndActiveOptions() {
        assertThrows(BizException.class,()->reports.submit(a,param("user",a,"自己",List.of())));
        for(String content:List.of(" ","字".repeat(201)))assertThrows(BizException.class,()->reports.submit(a,param("user",b,content,List.of())));
        assertThrows(BizException.class,()->reports.submit(a,param("user",b,"内容",Collections.nCopies(10,"1"))));
        long other=image(b,"image","ready"),video=image(a,"video","ready"),pending=image(a,"image","processing"),own=image(a,"image","ready");
        for(long asset:List.of(other,video,pending))assertThrows(BizException.class,()->reports.submit(a,param("user",b,"内容",List.of(""+asset))));
        assertThrows(BizException.class,()->reports.submit(a,param("user",b,"内容",List.of(""+own,""+own))));
        reports.deleteOption(Long.valueOf(type));assertThrows(BizException.class,()->reports.submit(a,param("user",b,"内容",List.of())));
    }
    @Test void countsUnicodeAndSupportsOptionCrud() {
        String emoji="😀".repeat(200);assertNotNull(reports.submit(a,param("user",b,emoji,List.of())));
        var item=reports.saveOption(new ReportService.OptionParam(null,"reason","新理由",-10),true);assertEquals(item.id(),reports.options().reasons().getFirst().id());
        reports.saveOption(new ReportService.OptionParam(Long.valueOf(item.id()),"reason","更新理由",20),false);reports.deleteOption(Long.valueOf(item.id()));assertTrue(reports.options().reasons().stream().noneMatch(x->x.id().equals(item.id())));
    }
    @Test void requiresLoginAndProtectsAdminEndpoints()throws Exception {
        mvc.perform(post("/app/report/submit").contentType("application/json").content("{}")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get("/report/list")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get("/app/report/options").header("Authorization","Bearer "+jwt.createAccessToken(a,"app"))).andExpect(jsonPath("$.code").value("0000"));
        mvc.perform(post("/app/user/"+b+"/block").header("Authorization","Bearer "+jwt.createAccessToken(a,"app")).contentType("application/json").content("{\"blocked\":true}"))
            .andExpect(jsonPath("$.data").value(true));
        mvc.perform(get("/app/user/"+b+"/profile").header("Authorization","Bearer "+jwt.createAccessToken(a,"app"))).andExpect(jsonPath("$.data.blockedByMe").value(true));
    }
}
