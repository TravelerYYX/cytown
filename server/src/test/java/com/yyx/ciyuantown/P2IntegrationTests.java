package com.yyx.ciyuantown;
import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.business.appauth.service.EmailCodeService;
import com.yyx.ciyuantown.business.appuser.service.AppUserService;
import com.yyx.ciyuantown.business.interaction.service.InteractionService;
import com.yyx.ciyuantown.common.util.JwtUtil;
import com.yyx.ciyuantown.common.exception.BizException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.*;
import org.apache.ibatis.session.SqlSession;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** 仅在本机已迁移开发库执行，邮件使用 mock；事务测试全部回滚。
 *  验证码用例基于 DB verify_code 表（DB 回退/关闭路径），通过下方 properties 固定
 *  ciyuantown.redis.enabled=false，不受全局开关（.env REDIS_ENABLED）影响；
 *  Redis 路径用例见 VerifyCodeRedisTests（REDIS_ENABLED=true 时执行）。 */
@SpringBootTest(properties={
    "spring.mail.username=p2-test@example.invalid", "spring.mail.password=test-only-no-network",
    "ciyuantown.upload-dir=./target/p2-test-uploads",
    "ciyuantown.redis.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class P2IntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtUtil jwt;
    @Autowired EmailCodeService codes;
    @Autowired AppUserService users;
    @Autowired InteractionService interactions;
    @Autowired SqlSession sqlSession;
    @MockitoBean JavaMailSender mail;
    String qq;
    @BeforeEach void setup() {
        qq = "9"+String.format("%010d",ThreadLocalRandom.current().nextLong(10_000_000_000L));
        when(mail.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }
    long createUser() {
        return new SimpleJdbcInsert(jdbc).withTableName("app_user").usingGeneratedKeyColumns("id")
            .usingColumns("qq_number","email","nickname")
            .executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname","测试町友")).longValue();
    }
    long createPost() {
        return new SimpleJdbcInsert(jdbc).withTableName("post").usingGeneratedKeyColumns("id")
            .usingColumns("author_nickname","title","status","like_count","favorite_count")
            .executeAndReturnKey(Map.of("author_nickname","测试作者","title","P2测试帖子","status",1,"like_count",5,"favorite_count",3)).longValue();
    }
    void code(String value, LocalDateTime expiration) {
        jdbc.update("INSERT INTO verify_code(email,code,expire_time) VALUES(?,?,?)",qq+"@qq.com",value,expiration);
        sqlSession.clearCache();
    }
    String appToken(long id) { return "Bearer "+jwt.createAccessToken(id,"app"); }
    String adminToken() { return "Bearer "+jwt.createAccessToken(jdbc.queryForObject("SELECT MIN(id) FROM admin_user",Long.class)); }
    JsonNode login(String value) throws Exception {
        return json.readTree(mvc.perform(post("/app/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("qqNumber",qq,"code",value))))
            .andReturn().getResponse().getContentAsString());
    }
    @Test void automaticRegistrationSingleUseAndRepeatLogin() throws Exception {
        code("000042",LocalDateTime.now().plusMinutes(5));
        var first=login("000042");
        assertEquals("0000",first.path("code").asText());
        String id=first.at("/data/userInfo/userId").asText();
        assertFalse(id.isBlank());
        assertEquals("町酱"+qq,first.at("/data/userInfo/nickname").asText());
        assertEquals("1000",login("000042").path("code").asText());
        code("000043",LocalDateTime.now().plusMinutes(5));
        assertEquals(id,login("000043").at("/data/userInfo/userId").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE qq_number=?",Integer.class,qq));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM verify_code WHERE email=? AND used=1",Integer.class,qq+"@qq.com"));
    }
    @Test void minePostsOnlyReturnsCurrentUsersVisibleAndPendingPosts() throws Exception {
        long owner=createUser();
        long other=new SimpleJdbcInsert(jdbc).withTableName("app_user").usingGeneratedKeyColumns("id")
            .usingColumns("qq_number","email","nickname")
            .executeAndReturnKey(Map.of("qq_number","8"+qq.substring(1),"email","8"+qq.substring(1)+"@qq.com","nickname","另一位町友")).longValue();
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,status) VALUES(?,?,?,0)",owner,"测试町友","待审核内容");
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,status) VALUES(?,?,?,1)",owner,"测试町友","已发布内容");
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,status) VALUES(?,?,?,2)",owner,"测试町友","已下架内容");
        jdbc.update("INSERT INTO post(author_id,author_nickname,title,status) VALUES(?,?,?,1)",other,"另一位町友","他人内容");

        mvc.perform(get("/app/post/mine?pageSize=100").header("Authorization",appToken(owner)))
            .andExpect(jsonPath("$.code").value("0000"))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.records[0].authorId").value(Long.toString(owner)))
            .andExpect(jsonPath("$.data.records[1].authorId").value(Long.toString(owner)));
        mvc.perform(get("/app/post/mine")).andExpect(jsonPath("$.code").value("8888"));
    }
    @Test void latestCodeWrongCodeAndExpiration() throws Exception {
        code("111111",LocalDateTime.now().plusMinutes(5));
        code("222222",LocalDateTime.now().plusMinutes(5));
        assertEquals("1000",login("111111").path("code").asText());
        assertEquals("1000",login("999999").path("code").asText());
        jdbc.update("UPDATE verify_code SET expire_time=? WHERE email=?",LocalDateTime.now().minusMinutes(1),qq+"@qq.com");
        sqlSession.clearCache();
        assertEquals("1000",login("222222").path("code").asText());
    }
    @Test void sendCodeHtmlAndResendThrottle() throws Exception {
        mvc.perform(post("/app/auth/sendCode").contentType("application/json").content("{\"qqNumber\":\"abc\"}"))
            .andExpect(jsonPath("$.code").value("1000"));
        codes.send(qq);
        var record=jdbc.queryForMap("SELECT code,expire_time,create_time FROM verify_code WHERE email=?",qq+"@qq.com");
        assertTrue(record.get("code").toString().matches("[0-9]{6}"));
        var capture=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(capture.capture());
        assertEquals("次元町验证码",capture.getValue().getSubject());
        assertTrue(capture.getValue().getContent().toString().contains(record.get("code").toString()));
        assertThrows(BizException.class,()->codes.send(qq));
        verify(mail,times(1)).send(any(MimeMessage.class));
    }
    @Test void dailyLimitBlocksEleventhSend() {
        for(int i=0;i<10;i++) jdbc.update(
            "INSERT INTO verify_code(email,code,create_time,expire_time) VALUES(?,'123456',?,?)",
            qq+"@qq.com",LocalDate.now().atStartOfDay(),LocalDateTime.now().plusMinutes(5));
        var e=assertThrows(BizException.class,()->codes.send(qq));
        assertEquals("今日发送次数已达上限",e.getMessage());
        verify(mail,never()).send(any(MimeMessage.class));
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void failedMailRollsBackReservation() {
        doThrow(new MailSendException("simulated transport error")).when(mail).send(any(MimeMessage.class));
        assertThrows(BizException.class,()->codes.send(qq));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM verify_code WHERE email=?",Integer.class,qq+"@qq.com"));
    }
    @Test void protectedRoutesAndCrossAudienceIsolation() throws Exception {
        long id=createUser();
        mvc.perform(get("/app/user/me")).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(get("/app/user/me").header("Authorization","Bearer broken")).andExpect(jsonPath("$.code").value("9999"));
        mvc.perform(get("/app/user/me").header("Authorization",adminToken())).andExpect(jsonPath("$.code").value("9999"));
        mvc.perform(post("/app-user/list").header("Authorization",appToken(id)).contentType("application/json").content("{}"))
            .andExpect(jsonPath("$.code").value("9999"));
        mvc.perform(get("/app/user/me").header("Authorization",appToken(id)))
            .andExpect(jsonPath("$.data.userId").value(Long.toString(id)));
        mvc.perform(post("/app/auth/refreshToken").contentType("application/json")
            .content(json.writeValueAsString(Map.of("refreshToken",jwt.createRefreshToken(id,"app")))))
            .andExpect(jsonPath("$.code").value("0000")).andExpect(jsonPath("$.data.userInfo.userId").value(Long.toString(id)));
        mvc.perform(post("/auth/refreshToken").contentType("application/json")
            .content(json.writeValueAsString(Map.of("refreshToken",jwt.createRefreshToken(id,"app")))))
            .andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/app/auth/refreshToken").contentType("application/json")
            .content(json.writeValueAsString(Map.of("refreshToken",jwt.createRefreshToken(id)))))
            .andExpect(jsonPath("$.code").value("8888"));
    }
    @Test void disabledUserCannotLoginRefreshOrWrite() throws Exception {
        long id=createUser();
        String token=appToken(id);
        users.updateStatus(id,1);
        code("123456",LocalDateTime.now().plusMinutes(5));
        assertEquals("账号已被禁用",login("123456").path("msg").asText());
        mvc.perform(get("/app/user/me").header("Authorization",token)).andExpect(jsonPath("$.code").value("8888"));
        mvc.perform(post("/app/auth/refreshToken").contentType("application/json")
            .content(json.writeValueAsString(Map.of("refreshToken",jwt.createRefreshToken(id,"app")))))
            .andExpect(jsonPath("$.code").value("8888"));
    }
    @Test void nicknameValidationAndAvatarValidation() throws Exception {
        long id=createUser();
        mvc.perform(post("/app/user/updateNickname").header("Authorization",appToken(id)).contentType("application/json").content("{\"nickname\":\"A\"}"))
            .andExpect(jsonPath("$.code").value("1000"));
        mvc.perform(post("/app/user/updateNickname").header("Authorization",appToken(id)).contentType("application/json").content("{\"nickname\":\"  新町友  \"}"))
            .andExpect(jsonPath("$.data.nickname").value("新町友"));
        mvc.perform(multipart("/app/user/updateAvatar").file(new MockMultipartFile("file","bad.txt","text/plain","test".getBytes()))
            .header("Authorization",appToken(id))).andExpect(jsonPath("$.code").value("1000"));
        mvc.perform(multipart("/app/user/updateAvatar").file(new MockMultipartFile("file","large.png","image/png",new byte[2*1024*1024+1]))
            .header("Authorization",appToken(id))).andExpect(jsonPath("$.code").value("1000"));
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0Z8AAAAASUVORK5CYII=");
        var result=mvc.perform(multipart("/app/user/updateAvatar").file(new MockMultipartFile("file","avatar.png","image/png",png))
            .header("Authorization",appToken(id))).andExpect(jsonPath("$.code").value("0000")).andReturn();
        String avatar=json.readTree(result.getResponse().getContentAsString()).at("/data/avatar").asText();
        assertTrue(avatar.startsWith("/uploads/storage/"));
        assertTrue(Files.exists(Path.of("target/p2-test-uploads/app-avatar",avatar.substring(avatar.lastIndexOf('/')+1))));
        // 文件会在测试事务回滚后的 synchronization 中删除。
    }
    @Test void interactionsUpdateCountsAndPersonalizedDetail() throws Exception {
        long id=createUser(), postId=createPost();
        mvc.perform(post("/app/interaction/toggleLike").contentType("application/json").content("{\"postId\":"+postId+"}"))
            .andExpect(jsonPath("$.code").value("8888"));
        for(String kind:List.of("Like","Favorite")) {
            int baseline=kind.equals("Like")?5:3;
            mvc.perform(post("/app/interaction/toggle"+kind).header("Authorization",appToken(id))
                .contentType("application/json").content("{\"postId\":"+postId+"}"))
                .andExpect(jsonPath("$.data.active").value(true)).andExpect(jsonPath("$.data.count").value(baseline+1));
        }
        mvc.perform(get("/app/post/"+postId).header("Authorization",appToken(id)))
            .andExpect(jsonPath("$.data.liked").value(true)).andExpect(jsonPath("$.data.favorited").value(true));
        mvc.perform(get("/app/post/"+postId)).andExpect(jsonPath("$.data.liked").value(false)).andExpect(jsonPath("$.data.favorited").value(false));
        assertFalse(interactions.toggleLike(id,postId).active());
        assertFalse(interactions.toggleFavorite(id,postId).active());
        assertEquals(5,jdbc.queryForObject("SELECT like_count FROM post WHERE id=?",Integer.class,postId));
        assertEquals(3,jdbc.queryForObject("SELECT favorite_count FROM post WHERE id=?",Integer.class,postId));
        jdbc.update("UPDATE post SET status=2 WHERE id=?",postId);
        sqlSession.clearCache();
        assertThrows(BizException.class,()->interactions.toggleLike(id,postId));
    }
    @Test void managementSearchPaginationAndStatusValidation() throws Exception {
        long id=createUser();
        mvc.perform(post("/app-user/list").header("Authorization",adminToken()).contentType("application/json")
            .content("{\"keyword\":\""+qq+"\",\"pageNum\":1,\"pageSize\":10}"))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].id").value(Long.toString(id)));
        mvc.perform(post("/app-user/list").header("Authorization",adminToken()).contentType("application/json").content("{\"pageSize\":101}"))
            .andExpect(jsonPath("$.code").value("1000"));
        mvc.perform(post("/app-user/updateStatus").header("Authorization",adminToken()).contentType("application/json")
            .content("{\"id\":"+id+",\"status\":2}")).andExpect(jsonPath("$.code").value("1000"));
        mvc.perform(post("/app-user/updateStatus").header("Authorization",adminToken()).contentType("application/json")
            .content("{\"id\":"+id+",\"status\":1}")).andExpect(jsonPath("$.code").value("0000"));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM app_user WHERE id=?",Integer.class,id));
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentTogglesSerializeAndRestoreBaseline() throws Exception {
        long id=createUser(), postId=createPost();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->interactions.toggleLike(id,postId));
            var b=executor.submit(()->interactions.toggleLike(id,postId));
            assertNotEquals(a.get(10,TimeUnit.SECONDS).active(),b.get(10,TimeUnit.SECONDS).active());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_like WHERE user_id=? AND post_id=?",Integer.class,id,postId));
            assertEquals(5,jdbc.queryForObject("SELECT like_count FROM post WHERE id=?",Integer.class,postId));
        } finally {
            jdbc.update("DELETE FROM user_like WHERE user_id=?",id);
            jdbc.update("DELETE FROM post WHERE id=?",postId);
            jdbc.update("DELETE FROM app_user WHERE id=?",id);
        }
    }
}
