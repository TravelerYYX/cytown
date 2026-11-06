package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.checkin.*;
import com.yyx.ciyuantown.business.level.*;
import com.yyx.ciyuantown.business.appuser.service.AppUserService;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"ciyuantown.redis.enabled=false"}) @Transactional
class CheckinIntegrationTests {
 @Autowired CheckinService checkins; @Autowired LevelService levels; @Autowired AppUserService users;
 @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @MockitoBean Clock clock;
 long user;
 void time(String date){var instant=LocalDate.parse(date).atTime(12,0).atZone(CheckinService.ZONE).toInstant();when(clock.getZone()).thenReturn(CheckinService.ZONE);when(clock.instant()).thenReturn(instant);when(clock.millis()).thenReturn(instant.toEpochMilli());}
 @BeforeEach void setup() throws Exception {
  time("2026-10-23");
  db.update("UPDATE checkin_config SET version=1,config_json=? WHERE id=1",json.writeValueAsString(CheckinConfig.defaults()));
  db.update("UPDATE community_level_config SET version=0,config_json=? WHERE id=1",json.writeValueAsString(LevelConfig.defaults()));
  String qq="8"+String.format("%010d",java.util.concurrent.ThreadLocalRandom.current().nextLong(10000000000L));
  user=new SimpleJdbcInsert(db).withTableName("app_user").usingGeneratedKeyColumns("id").usingColumns("qq_number","email","nickname").executeAndReturnKey(Map.of("qq_number",qq,"email",qq+"@qq.com","nickname","签到测试")).longValue();
 }
 @ParameterizedTest @CsvSource({"2026-02-01,28","2028-02-01,29","2026-04-01,30","2026-10-01,31"})
 void calendarUsesRealMonth(String date,int count){time(date);var s=checkins.state(user);assertEquals(count,s.daysInMonth());assertEquals(count,s.days().size());assertEquals(0,s.missedDays());}
 @Test void oldAndNewEntryShareOneAtomicReward(){var a=checkins.claim(user,null);assertEquals(10,a.gained());assertEquals(0,a.state().balance().compareTo(BigDecimal.valueOf(10)));assertTrue(a.state().signedToday());assertEquals(0,levels.signIn(user).gained());assertTrue(checkins.claim(user,null).alreadySigned());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_coin_ledger WHERE user_id=?",Integer.class,user));assertEquals(10,db.queryForObject("SELECT experience FROM app_user WHERE id=?",Long.class,user));}
 @Test void makeupChargesBeforeAwardAndDoesNotRepairStreak(){checkins.claim(user,null);var r=checkins.claim(user,LocalDate.of(2026,10,22));assertEquals(0,r.state().balance().compareTo(BigDecimal.valueOf(10)));assertEquals(2,r.state().signedDays());assertEquals(1,r.progress().streakDays());assertTrue(r.state().days().get(21).makeup());assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM user_coin_ledger WHERE user_id=?",Integer.class,user));assertTrue(checkins.claim(user,LocalDate.of(2026,10,22)).alreadySigned());assertEquals(0,checkins.state(user).balance().compareTo(BigDecimal.valueOf(10)));}
 @Test void insufficientCoinsCannotBePaidWithReward(){assertThrows(BizException.class,()->checkins.claim(user,LocalDate.of(2026,10,22)));assertEquals(0,checkins.state(user).balance().compareTo(BigDecimal.valueOf(0)));assertEquals(0,checkins.state(user).signedDays());}
 @Test void invalidDatesNeverCharge(){assertThrows(BizException.class,()->checkins.claim(user,LocalDate.of(2026,9,30)));assertThrows(BizException.class,()->checkins.claim(user,LocalDate.of(2026,10,24)));}
 @Test void staleMakeupConfirmationDoesNotCharge(){db.update("UPDATE app_user SET coin_balance=50 WHERE id=?",user);var c=checkins.config();checkins.save(c);assertThrows(BizException.class,()->checkins.claim(user,LocalDate.of(2026,10,22),c.version()));assertEquals(0,checkins.state(user).balance().compareTo(BigDecimal.valueOf(50)));assertEquals(0,checkins.state(user).signedDays());}
 @Test void legacyLedgerKeysDoNotBlockFreshCalendar(){db.update("INSERT INTO user_level_ledger(user_id,action,event_key,amount,earned_date) VALUES(?,'sign_in','2026-10-23',10,'2026-10-23')",user);assertEquals(10,checkins.claim(user,null).gained());}
 @Test void actualStreakKeepsWeeklyBonus(){for(int i=1;i<=6;i++)db.update("INSERT INTO user_level_signin(user_id,sign_date,streak_days) VALUES(?,?,?)",user,LocalDate.of(2026,10,16+i),i);assertEquals(40,checkins.claim(user,null).gained());assertEquals(7,levels.progress(user).streakDays());}
 @Test void makeupYesterdayDoesNotJoinActualStreak(){db.update("INSERT INTO user_level_signin(user_id,sign_date,streak_days,makeup) VALUES(?,'2026-10-22',6,TRUE)",user);assertEquals(10,checkins.claim(user,null).gained());assertEquals(1,levels.progress(user).streakDays());}
 @Test void snapshotAndUnclaimedDatesUseDifferentVersions(){checkins.claim(user,null);var c=checkins.config();var days=new ArrayList<>(c.days());days.set(22,new CheckinConfig.Day(23,80,90,"",0,"coins"));days.set(23,new CheckinConfig.Day(24,80,90,"",0,"experience"));checkins.save(new CheckinConfig(c.version(),20,c.coinIcon(),c.experienceIcon(),c.frames(),days));assertEquals(0,checkins.state(user).days().get(22).reward().coins().compareTo(BigDecimal.valueOf(10)));time("2026-10-24");var r=checkins.claim(user,null);assertEquals(90,r.gained());assertEquals(0,r.state().balance().compareTo(BigDecimal.valueOf(90)));}
 void frameDays(int duration){var c=checkins.config();var days=new ArrayList<>(c.days());for(int i=0;i<31;i++)days.set(i,new CheckinConfig.Day(i+1,10,10,"xingxi",duration,"frame"));checkins.save(new CheckinConfig(c.version(),c.makeupPrice(),c.coinIcon(),c.experienceIcon(),c.frames(),days));}
 @Test void timedFrameStartsAtClaimRenewsAndCannotDowngradePermanent(){frameDays(3);checkins.claim(user,null);long first=checkins.requireFrame(user,"xingxi");assertEquals(LocalDate.of(2026,10,26).atTime(12,0).atZone(CheckinService.ZONE).toInstant().toEpochMilli(),first);var worn=users.updateAvatarFrame(user,"xingxi");assertEquals(first,worn.avatarFrameExpiresAt());time("2026-10-24");checkins.claim(user,null);assertEquals(first+3*86400000L,checkins.requireFrame(user,"xingxi"));frameDays(0);time("2026-10-25");checkins.claim(user,null);assertNull(checkins.requireFrame(user,"xingxi"));frameDays(1);time("2026-10-26");checkins.claim(user,null);assertNull(checkins.requireFrame(user,"xingxi"));assertNull(users.me(user).avatarFrameExpiresAt());}
 @Test void expiredAndUnownedFramesAreRejectedButFreeFramesRemain(){assertThrows(BizException.class,()->users.updateAvatarFrame(user,"xingxi"));assertEquals("star-sea",users.updateAvatarFrame(user,"star-sea").avatarFrame());frameDays(1);checkins.claim(user,null);time("2026-10-25");assertThrows(BizException.class,()->users.updateAvatarFrame(user,"xingxi"));}
 @Test void monthRolloverResetsCalendarNotBalance(){checkins.claim(user,null);time("2026-11-01");var s=checkins.state(user);assertEquals(0,s.signedDays());assertEquals(30,s.daysInMonth());assertEquals(0,s.balance().compareTo(BigDecimal.valueOf(10)));assertEquals(1,checkins.claim(user,null).progress().streakDays());}
 @Test void midnightUsesShanghai(){when(clock.instant()).thenReturn(Instant.parse("2026-10-23T15:59:59Z"));assertEquals("2026-10-23",checkins.state(user).today());when(clock.instant()).thenReturn(Instant.parse("2026-10-23T16:00:00Z"));assertEquals("2026-10-24",checkins.state(user).today());}
 @Test void recommendationPoolIsExplicitPublishedAndDistinct(){assertTrue(checkins.recommendations().isEmpty());List<Long> ids=new ArrayList<>();for(int i=0;i<5;i++){ids.add(new SimpleJdbcInsert(db).withTableName("post").usingGeneratedKeyColumns("id").usingColumns("title","author_nickname","status","checkin_recommended","create_time").executeAndReturnKey(Map.of("title","推荐"+i,"author_nickname","测试","status",i==4?2:1,"checkin_recommended",i!=3,"create_time",LocalDateTime.of(2026,10,20+i,12,0))).longValue());}var rows=checkins.recommendations();assertEquals(3,rows.size());assertEquals(ids.get(2).toString(),rows.getFirst().get("id"));assertEquals(3,rows.stream().map(r->r.get("id")).distinct().count());db.update("UPDATE post SET checkin_recommended=FALSE");assertTrue(checkins.recommendations().isEmpty());}
 @Test void configRejectsConflictsInvalidPrimaryAndMissingFrames(){var c=checkins.config();checkins.save(c);assertThrows(BizException.class,()->checkins.save(c));var days=new ArrayList<>(c.days());days.set(0,new CheckinConfig.Day(1,0,0,"",0,"coins"));assertThrows(BizException.class,()->new CheckinConfig(1,10,c.coinIcon(),c.experienceIcon(),c.frames(),days).validate());}
}
