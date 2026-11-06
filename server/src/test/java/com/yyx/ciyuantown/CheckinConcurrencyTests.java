package com.yyx.ciyuantown;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.checkin.*;
import com.yyx.ciyuantown.business.level.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Committed concurrency fixtures are only allowed in the disposable verifier database. */
@SpringBootTest(properties={"ciyuantown.redis.enabled=false"})
@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB",matches="true")
class CheckinConcurrencyTests {
 @Autowired CheckinService checkins;@Autowired LevelService levels;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
 long user;String configBackup;long configVersion;
 @BeforeEach void setup()throws Exception{
  configBackup=db.queryForObject("SELECT config_json FROM checkin_config WHERE id=1",String.class);configVersion=db.queryForObject("SELECT version FROM checkin_config WHERE id=1",Long.class);
  db.update("UPDATE checkin_config SET version=1,config_json=? WHERE id=1",json.writeValueAsString(CheckinConfig.defaults()));
  String qq="7"+String.format("%010d",ThreadLocalRandom.current().nextLong(10000000000L));
  user=new SimpleJdbcInsert(db).withTableName("app_user").usingGeneratedKeyColumns("id").usingColumns("qq_number","email","nickname").executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname","并发签到测试")).longValue();
 }
 @AfterEach void cleanup(){for(String table:List.of("user_coin_ledger","user_avatar_frame","user_level_signin","user_level_ledger"))db.update("DELETE FROM "+table+" WHERE user_id=?",user);db.update("DELETE FROM app_user WHERE id=?",user);db.update("UPDATE checkin_config SET version=?,config_json=? WHERE id=1",configVersion,configBackup);db.execute("DROP TRIGGER IF EXISTS checkin_test_failure");}
 @Test void oldAndNewEndpointsContendOnSameUserLock()throws Exception{
  try(var workers=Executors.newFixedThreadPool(6)){var start=new CountDownLatch(1);List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<6;i++){boolean legacy=i%2==0;tasks.add(workers.submit(()->{try{start.await();if(legacy)levels.signIn(user);else checkins.claim(user,null);}catch(InterruptedException e){throw new RuntimeException(e);}}));}start.countDown();for(var task:tasks)task.get(20,TimeUnit.SECONDS);}
  assertEquals(0,checkins.state(user).balance().compareTo(BigDecimal.valueOf(10)));assertEquals(1,checkins.state(user).signedDays());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=? AND action='sign_in'",Integer.class,user));
 }
 @Test void concurrentMakeupDeductsAndGrantsExactlyOnce()throws Exception{
  LocalDate date=LocalDate.now(CheckinService.ZONE).minusDays(1);if(date.getMonth()!=LocalDate.now(CheckinService.ZONE).getMonth())return;
  db.update("UPDATE app_user SET coin_balance=50 WHERE id=?",user);
  try(var workers=Executors.newFixedThreadPool(4)){List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<4;i++)tasks.add(workers.submit(()->checkins.claim(user,date)));for(var task:tasks)task.get(20,TimeUnit.SECONDS);}
  assertEquals(0,checkins.state(user).balance().compareTo(BigDecimal.valueOf(50)));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM user_coin_ledger WHERE user_id=?",Integer.class,user));
 }
 @Test void storageFailureRollsBackSignDateBalanceAndRewards(){
  db.execute("CREATE TRIGGER checkin_test_failure BEFORE INSERT ON user_coin_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='simulated ledger failure'");
  assertThrows(RuntimeException.class,()->checkins.claim(user,null));assertEquals(0,checkins.state(user).balance().compareTo(BigDecimal.valueOf(0)));assertEquals(0,checkins.state(user).signedDays());assertEquals(0,db.queryForObject("SELECT experience FROM app_user WHERE id=?",Long.class,user));
 }
}
