package com.yyx.ciyuantown.business.membership;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.appuser.service.AppUserAccessService;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.checkin.*;
import com.yyx.ciyuantown.business.level.*;
import com.yyx.ciyuantown.common.storage.ResourceCleanupService;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(MembershipTests.Config.class)
class MembershipTests {
 @Configuration @EnableTransactionManagement @Import({MembershipStore.class,LevelService.class,CheckinService.class})
 static class Config {
  @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:membership;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");}
  @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds){
   @Override public java.util.List<java.util.Map<String,Object>> queryForList(String sql){return super.queryForList(sql.replace(" FOR SHARE"," FOR UPDATE"));}
  };}
  @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
  @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
  @Bean Clock clock(){var c=mock(Clock.class);when(c.instant()).thenReturn(Instant.parse("2026-11-04T02:00:00Z"));when(c.getZone()).thenReturn(MembershipStore.ZONE);return c;}
  @Bean AppUserAccessService access(JdbcTemplate db){var a=mock(AppUserAccessService.class);when(a.requireForUpdate(anyLong())).thenAnswer(i->{Long id=i.getArgument(0);var rows=db.queryForList("SELECT id FROM app_user WHERE id=? AND status=0 AND deleted=0 FOR UPDATE",id);if(rows.isEmpty())throw new BizException("用户不可用");return new AppUser();});return a;}
  @Bean ResourceCleanupService cleanup(){return mock(ResourceCleanupService.class);}
  @Bean org.mybatis.spring.SqlSessionTemplate session(){return mock(org.mybatis.spring.SqlSessionTemplate.class);}
 }
 @Autowired MembershipStore store;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired Clock clock;@Autowired CheckinService checkin;@Autowired PlatformTransactionManager tx;
 @BeforeEach void setup() throws Exception {
  when(clock.instant()).thenReturn(Instant.parse("2026-11-04T02:00:00Z"));
  db.execute("DROP ALL OBJECTS");
  db.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY,deleted INT DEFAULT 0,status INT DEFAULT 0,coin_balance BIGINT DEFAULT 0,experience BIGINT DEFAULT 0,level INT DEFAULT 1,avatar_frame VARCHAR(32),avatar_frame_expires_at BIGINT)");
  db.execute("INSERT INTO app_user(id) VALUES(1),(2)");
  db.execute("CREATE TABLE user_avatar_frame(user_id BIGINT,frame_id VARCHAR(32),expires_at DATETIME,PRIMARY KEY(user_id,frame_id))");
  db.execute("CREATE TABLE community_level_config(id INT PRIMARY KEY,version BIGINT,config_json LONGTEXT)");
  db.update("INSERT INTO community_level_config VALUES(1,1,?)",json.writeValueAsString(LevelConfig.defaults()));
  db.execute("CREATE TABLE checkin_config(id INT PRIMARY KEY,version BIGINT,config_json LONGTEXT)");
  db.update("INSERT INTO checkin_config VALUES(1,1,?)",json.writeValueAsString(CheckinConfig.defaults()));
  db.execute("CREATE TABLE user_level_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,action VARCHAR(32),event_key VARCHAR(160),amount BIGINT,earned_date DATE,UNIQUE(user_id,action,event_key))");
  db.execute("CREATE TABLE user_coin_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,event_key VARCHAR(160),amount BIGINT,balance_after BIGINT,UNIQUE(user_id,event_key))");
  String migration=Files.readString(Path.of("doc/sql/p34-membership.sql"));
  migration=migration.replaceAll("(?m)^--.*$", "").replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "").replaceAll("KEY idx_membership_[^\n]+", "");
  migration=migration.replace("UNIQUE KEY uk_membership_trade(trade_no),", "UNIQUE KEY uk_membership_trade(trade_no)");
  for(String sql:migration.split(";"))if(!sql.isBlank())db.execute(sql);
  var c=MembershipConfig.defaults();var plans=c.plans().stream().map(p->new MembershipConfig.Plan(p.id(),p.name(),p.description(),p.priceFen(),20,10,p.badgeUrl(),p.frameUrl(),true,false,p.benefits())).toList();
  store.save(new MembershipConfig(c.version(),true,"公告","反馈","测试付费协议",plans));
 }
 MembershipStore.Order create(String plan,String key){return store.create(1L,new MembershipStore.Create(plan,store.config().version(),"APP",key,true),"profile1");}
 void activate(MembershipStore.Order o){store.paid(o.id(),"trade-"+o.id(),o.amountFen());store.fulfill(o.id());}
 @Test void purchaseRenewalAndExpiryRetainRewards(){var first=create("go","request-0001");activate(first);var one=store.member(1L);assertTrue(one.active());assertEquals(LocalDateTime.of(2026,12,4,10,0),store.order(first.id()).grantEnd());
  store.paid(first.id(),"trade-"+first.id(),900);store.fulfill(first.id());assertEquals(10L,db.queryForObject("SELECT coin_balance FROM app_user WHERE id=1",Long.class));assertEquals(20L,db.queryForObject("SELECT experience FROM app_user WHERE id=1",Long.class));
  assertThrows(BizException.class,()->create("plus","request-0002"));var renewal=create("go","request-0003");activate(renewal);assertEquals(LocalDateTime.of(2027,1,4,10,0),store.order(renewal.id()).grantEnd());
  when(clock.instant()).thenReturn(Instant.parse("2027-01-04T02:00:01Z"));assertFalse(store.member(1L).active());assertThrows(BizException.class,()->store.requireFrame(1L,"mem-go"));assertEquals(20L,db.queryForObject("SELECT coin_balance FROM app_user WHERE id=1",Long.class));assertEquals(40L,db.queryForObject("SELECT experience FROM app_user WHERE id=1",Long.class));assertDoesNotThrow(()->create("plus","request-0004"));
 }
 @Test void monthEndPurchaseUsesCalendarMonth(){when(clock.instant()).thenReturn(Instant.parse("2027-01-31T02:00:00Z"));var o=create("go","request-month-end");activate(o);assertEquals(LocalDateTime.of(2027,2,28,10,0),store.order(o.id()).grantEnd());assertEquals(store.member(1L).expiresAt(),store.requireFrame(1L,"mem-go"));}
 @Test void renewalPreservesExistingExpiryAndHandlesLeapMonth(){db.update("INSERT INTO user_membership(user_id,plan_id,expires_at) VALUES(1,'go',?)",LocalDateTime.of(2028,1,31,10,0));var o=create("go","request-leap-month");assertEquals(LocalDateTime.of(2028,1,31,10,0).atZone(MembershipStore.ZONE).toInstant().toEpochMilli(),store.member(1L).expiresAt());activate(o);assertEquals(LocalDateTime.of(2028,1,31,10,0),store.order(o.id()).grantStart());assertEquals(LocalDateTime.of(2028,2,29,10,0),store.order(o.id()).grantEnd());}
 @Test void snapshotSurvivesPriceChangeAndStop(){var o=create("go","request-0001");var c=store.config();var plans=c.plans().stream().map(p->new MembershipConfig.Plan(p.id(),"新名称",p.description(),4900,99,99,p.badgeUrl(),p.frameUrl(),false,false,p.benefits())).toList();store.save(new MembershipConfig(c.version(),false,"","","测试协议",plans));activate(o);assertEquals(900,store.order(o.id()).amountFen());assertEquals(10L,db.queryForObject("SELECT coin_balance FROM app_user WHERE id=1",Long.class));assertEquals("新名称",store.member(1L).plan().name());assertThrows(BizException.class,()->create("go","request-0002"));}
 @Test void staleVersionAgreementAndOwnershipRejected(){assertThrows(BizException.class,()->store.create(1L,new MembershipStore.Create("go",1,"APP","request-0001",true),"p"));assertThrows(BizException.class,()->store.create(1L,new MembershipStore.Create("go",store.config().version(),"APP","request-0001",false),"p"));var o=create("go","request-0001");assertEquals(o.id(),create("go","request-0001").id());assertThrows(BizException.class,()->store.owned(2L,o.id()));assertThrows(BizException.class,()->create("plus","request-0001"));assertThrows(BizException.class,()->store.paid(o.id(),"trade",1));}
 @Test void concurrentCreateAndFulfillOnlyOnce() throws Exception {try(var pool=Executors.newFixedThreadPool(2)){var latch=new CountDownLatch(1);var a=pool.submit(()->{latch.await();return create("go","request-0001");});var b=pool.submit(()->{latch.await();return create("go","request-0001");});latch.countDown();var o=a.get(10,TimeUnit.SECONDS);assertEquals(o.id(),b.get(10,TimeUnit.SECONDS).id());store.paid(o.id(),"trade",900);var f=pool.submit(()->store.fulfill(o.id()));var g=pool.submit(()->store.fulfill(o.id()));f.get(10,TimeUnit.SECONDS);g.get(10,TimeUnit.SECONDS);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_coin_ledger",Integer.class));assertEquals(20L,db.queryForObject("SELECT experience FROM app_user WHERE id=1",Long.class));}}
 @Test void ledgerFailureRollsBackGrantButKeepsPaid(){var o=create("go","request-0001");store.paid(o.id(),"trade",900);db.update("INSERT INTO user_coin_ledger(user_id,event_key,amount,balance_after) VALUES(1,?,0,0)","membership:"+o.id());assertThrows(RuntimeException.class,()->store.fulfill(o.id()));assertEquals("PAID",store.order(o.id()).status());assertFalse(store.member(1L).active());assertEquals(0L,db.queryForObject("SELECT experience FROM app_user WHERE id=1",Long.class));db.update("DELETE FROM user_coin_ledger");store.fulfill(o.id());assertEquals("ACTIVE",store.order(o.id()).status());}
 @Test void permanentFrameFromOtherSourceRemainsAfterMembership(){var o=create("go","request-0001");activate(o);db.update("INSERT INTO user_avatar_frame VALUES(1,'mem-go',NULL)");when(clock.instant()).thenReturn(Instant.parse("2028-11-04T02:00:00Z"));assertNull(new TransactionTemplate(tx).execute(s->checkin.requireFrame(1L,"mem-go")));assertThrows(BizException.class,()->new TransactionTemplate(tx).execute(s->checkin.requireFrame(2L,"mem-go")));}
 @Test void shorterCheckinGrantDoesNotHideActiveMemberFrame(){var o=create("go","request-0001");activate(o);long memberEnd=store.requireFrame(1L,"mem-go");db.update("UPDATE app_user SET avatar_frame='mem-go',avatar_frame_expires_at=? WHERE id=1",memberEnd);
  new TransactionTemplate(tx).executeWithoutResult(t->org.springframework.test.util.ReflectionTestUtils.invokeMethod((Object)org.springframework.test.util.AopTestUtils.getTargetObject(checkin),"grantFrame",1L,"mem-go",1));
  assertEquals(memberEnd,db.queryForObject("SELECT avatar_frame_expires_at FROM app_user WHERE id=1",Long.class));
 }
 @Test void archiveAndConfigValidation(){var c=store.config();assertThrows(BizException.class,()->store.save(new MembershipConfig(c.version(),true,"","","",c.plans())));assertEquals(List.of(900L,1900L,2900L,3900L,4900L),MembershipConfig.defaults().plans().stream().map(MembershipConfig.Plan::priceFen).toList());}
 MembershipConfig removePlan(String id){var c=store.config();return store.save(new MembershipConfig(c.version(),c.selling(),c.notice(),c.contact(),c.agreement(),c.plans().stream().filter(p->!p.id().equals(id)).toList()));}
 @Test void deletedPlanPreservesActiveMemberAndFrames(){var o=create("go","request-delete-active");activate(o);var before=store.member(1L);var c=removePlan("go");
  var removed=c.plans().stream().filter(p->p.id().equals("go")).findFirst().orElseThrow();assertTrue(removed.deleted());assertTrue(removed.archived());assertFalse(removed.selling());
  assertTrue(store.member(1L).active());assertEquals(before.plan().badgeUrl(),store.member(1L).plan().badgeUrl());assertEquals(before.expiresAt(),store.member(1L).expiresAt());
  assertEquals(before.expiresAt(),store.requireFrame(1L,"mem-go"));assertTrue(store.frames().stream().anyMatch(f->f.get("id").equals("mem-go")));assertEquals("Go",store.order(o.id()).plan().name());
  assertNotNull(store.summaries(List.of(1L)).get("1").plan());assertThrows(BizException.class,()->create("go","request-deleted-new"));
 }
 @Test void deletedPlanStillFulfillsPendingPayment(){var o=create("go","request-delete-pending");removePlan("go");activate(o);assertTrue(store.member(1L).active());assertNotNull(store.member(1L).plan());assertEquals(10L,db.queryForObject("SELECT coin_balance FROM app_user WHERE id=1",Long.class));assertEquals("ACTIVE",store.order(o.id()).status());}
 @Test void deletedPlansCannotBeRestoredOrOverwritten(){var original=store.config();removePlan("go");var c=store.config();var saved=store.save(new MembershipConfig(c.version(),false,"","","",original.plans()));assertTrue(saved.plans().stream().filter(p->p.id().equals("go")).findFirst().orElseThrow().deleted());assertThrows(BizException.class,()->store.save(original));}
 @Test void deletingAllPlansRetainsHistoryAndAllowsNewPlan(){var c=store.config();var empty=store.save(new MembershipConfig(c.version(),false,"","","",List.of()));assertTrue(empty.plans().stream().allMatch(MembershipConfig.Plan::deleted));var fresh=new MembershipConfig.Plan("new-member","新会员","",900,0,0,"","",false,false,List.of());var saved=store.save(new MembershipConfig(empty.version(),false,"","","",List.of(fresh)));assertEquals(1,saved.plans().stream().filter(p->!p.deleted()).count());assertEquals(6,saved.plans().size());}
}
