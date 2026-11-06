package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.level.*;
import com.yyx.ciyuantown.business.interaction.service.InteractionService;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

/** Committed transactions, exclusively inside the disposable database verifier. */
@EnabledIfEnvironmentVariable(named="CIYUANTOWN_ACCOUNT_TEST_DB",matches="true")
@SpringBootTest(properties="ciyuantown.redis.enabled=false")
class LevelConcurrencyTests {
    @Autowired JdbcTemplate db;
    @Autowired LevelService levels;
    @Autowired InteractionService interactions;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired ExperienceAdjustmentService adjustments;
    @Autowired com.yyx.ciyuantown.business.appuser.service.LoginAccountService accounts;
    @Autowired org.mybatis.spring.SqlSessionTemplate session;
    long a,b;
    @BeforeEach void prepare() throws Exception {
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_"));
        db.update("UPDATE community_level_config SET version=0,config_json=? WHERE id=1",json.writeValueAsString(LevelConfig.defaults()));
        a=user();b=user();
    }
    long user(){return insert("app_user",Map.of("email",UUID.randomUUID()+"@level.test","nickname","并发测试"));}
    long insert(String table,Map<String,Object> body){return new SimpleJdbcInsert(db).withTableName(table).usingGeneratedKeyColumns("id").usingColumns(body.keySet().toArray(String[]::new)).executeAndReturnKey(body).longValue();}
    long operator(){return insert("admin_user",Map.of("username","adj_"+UUID.randomUUID().toString().substring(0,10),"password","test-only","nickname","并发管理员","user_type",0));}
    com.yyx.ciyuantown.business.appuser.dto.ExperienceAdjustmentParam adjustment(long delta,String key){
        return new com.yyx.ciyuantown.business.appuser.dto.ExperienceAdjustmentParam(a,delta,"并发调整",key);
    }
    long exp(long id){return db.queryForObject("SELECT experience FROM app_user WHERE id=?",Long.class,id);}
    List<Long> race(int count,IntFunction<Long> task) throws Exception {
        try(var pool=Executors.newFixedThreadPool(count)) {
            var start=new CountDownLatch(1);List<Future<Long>> futures=new ArrayList<>();
            for(int i=0;i<count;i++){int index=i;futures.add(pool.submit(()->{start.await();return task.apply(index);}));}
            start.countDown();List<Long> values=new ArrayList<>();for(var f:futures)values.add(f.get(30,TimeUnit.SECONDS));return values;
        }
    }
    @Test void concurrentSignInRewardsExactlyOnce() throws Exception {
        assertEquals(10,race(12,i->levels.signIn(a).gained()).stream().mapToLong(Long::longValue).sum());assertEquals(10,exp(a));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_level_signin WHERE user_id=?",Integer.class,a));
    }
    @Test void earlierRepeatableReadSnapshotsCannotExceedTheCap() throws Exception {
        var ready=new CountDownLatch(8);
        race(8,i->new TransactionTemplate(manager).execute(status->{
            db.queryForObject("SELECT experience FROM app_user WHERE id=?",Long.class,a);ready.countDown();
            try{assertTrue(ready.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
            levels.award(a,"post","snapshot:"+i);return 0L;
        }));
        assertEquals(60,exp(a));assertEquals(8,db.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=?",Integer.class,a));
    }
    @Test void simultaneousDuplicateEventsAndRollbackAreSafe() throws Exception {
        race(10,i->levels.award(a,"post","same-post"));assertEquals(20,exp(a));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=?",Integer.class,a));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(status->{levels.award(a,"comment","rollback");throw new IllegalStateException("rollback");}));
        assertEquals(20,exp(a));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=? AND event_key='rollback'",Integer.class,a));
    }
    @Test void oppositePostLikesUseConsistentUserLockOrder() throws Exception {
        long postA=insert("post",Map.of("author_id",a,"author_nickname","a","title","A","status",1));
        long postB=insert("post",Map.of("author_id",b,"author_nickname","b","title","B","status",1));
        race(12,i->{interactions.toggleLike(i%2==0?a:b,i%2==0?postB:postA);return 0L;});
        assertEquals(1,exp(a));assertEquals(1,exp(b));
    }
    @Test void rewardSeesConfigurationCommittedAfterItsSnapshot() throws Exception {
        var snapshot=new CountDownLatch(1);var updated=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var reward=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                db.queryForObject("SELECT experience FROM app_user WHERE id=?",Long.class,a);snapshot.countDown();
                try{assertTrue(updated.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
                return levels.award(a,"comment","new-config");
            }));
            assertTrue(snapshot.await(10,TimeUnit.SECONDS));var c=levels.config();var rules=new ArrayList<>(c.rules());
            rules.replaceAll(r->r.action().equals("comment")?new LevelConfig.Rule(r.action(),r.name(),7,7,true):r);
            levels.save(new LevelConfig(c.version(),c.tiers(),c.levels(),rules));updated.countDown();assertEquals(7,reward.get(20,TimeUnit.SECONDS));
        }
        assertEquals(7,exp(a));
    }
    @Test void concurrentConfigSavesRejectTheStaleVersion() throws Exception {
        var c=levels.config();var values=race(2,i->{try{levels.save(c);return 1L;}catch(BizException e){assertTrue(e.getMessage().contains("修改"));return 0L;}});
        assertEquals(1,values.stream().mapToLong(Long::longValue).sum());assertEquals(1,levels.config().version());
    }
    @Test void duplicateManualRequestsCommitOnlyOnce() throws Exception {
        long operator=operator();var request=adjustment(50,UUID.randomUUID().toString());
        var ids=race(12,i->Long.parseLong(adjustments.adjust(operator,false,request).id()));
        assertEquals(1,new HashSet<>(ids).size());assertEquals(50,exp(a));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=?",Integer.class,a));
    }
    @Test void manualAndAutomaticAwardsCannotOverwriteEachOther() throws Exception {
        long operator=operator();
        race(12,i->{if(i%2==0)return adjustments.adjust(operator,false,adjustment(5,UUID.randomUUID().toString())).delta();
            return levels.award(a,"post","concurrent-post:"+i);});
        assertEquals(90,exp(a));
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=? AND action='admin_adjust'",Integer.class,a));
        assertEquals(90,db.queryForObject("SELECT SUM(amount) FROM user_level_ledger WHERE user_id=?",Long.class,a));
    }
    @Test void racingDeductionsCannotMakeBalanceNegative() throws Exception {
        long operator=operator();db.update("UPDATE app_user SET experience=5 WHERE id=?",a);
        var results=race(2,i->{try{adjustments.adjust(operator,false,adjustment(-5,UUID.randomUUID().toString()));return 1L;}catch(BizException e){return 0L;}});
        assertEquals(1,results.stream().mapToLong(Long::longValue).sum());assertEquals(0,exp(a));
    }
    @Test void adjustmentUsesConfigCommittedAfterAnEarlierSnapshot() throws Exception {
        long operator=operator();var snapshot=new CountDownLatch(1);var updated=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var operation=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                db.queryForObject("SELECT experience FROM app_user WHERE id=?",Long.class,a);snapshot.countDown();
                try{assertTrue(updated.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
                return adjustments.adjust(operator,false,adjustment(100,UUID.randomUUID().toString()));
            }));
            assertTrue(snapshot.await(10,TimeUnit.SECONDS));var c=levels.config();var items=new ArrayList<>(c.levels());
            items.set(1,new LevelConfig.Level(2,150,items.get(1).badgeUrl()));
            levels.save(new LevelConfig(c.version(),c.tiers(),items,c.rules()));updated.countDown();
            assertEquals(1,operation.get(20,TimeUnit.SECONDS).afterLevel());
        }
        assertEquals(100,exp(a));
    }
    @Test void cacheEvictionOccursAfterCommitAndRollbackRetainsDataAndCaches() {
        long operator=operator();
        var cacheManager=new org.springframework.cache.concurrent.ConcurrentMapCacheManager("post-list","post-detail");
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();factory.addBean("cacheManager",cacheManager);
        var redis=org.mockito.Mockito.mock(com.yyx.ciyuantown.common.redis.RedisHotspotService.class);
        var service=new ExperienceAdjustmentService(db,accounts,levels,session,redis,factory.getBeanProvider(org.springframework.cache.CacheManager.class));
        var cache=cacheManager.getCache("post-detail");assertNotNull(cache);cache.put("fixture","old");
        new TransactionTemplate(manager).execute(status->{
            service.adjust(operator,false,adjustment(10,UUID.randomUUID().toString()));
            assertNotNull(cache.get("fixture"));org.mockito.Mockito.verifyNoInteractions(redis);return null;
        });
        assertNull(cache.get("fixture"));org.mockito.Mockito.verify(redis).evict("ct:user:profile:"+a);assertEquals(10,exp(a));
        cache.put("fixture","kept");org.mockito.Mockito.clearInvocations(redis);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(status->{
            service.adjust(operator,false,adjustment(20,UUID.randomUUID().toString()));throw new IllegalStateException("rollback");
        }));
        assertEquals(10,exp(a));assertNotNull(cache.get("fixture"));org.mockito.Mockito.verifyNoInteractions(redis);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_level_ledger WHERE user_id=?",Integer.class,a));
    }
    @Test void cacheFailureAfterCommitDoesNotTurnAdjustmentIntoAnApiFailure() {
        long operator=operator();var cacheManager=org.mockito.Mockito.mock(org.springframework.cache.CacheManager.class);
        org.mockito.Mockito.when(cacheManager.getCache(org.mockito.Mockito.anyString())).thenThrow(new IllegalStateException("cache offline"));
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();factory.addBean("cacheManager",cacheManager);
        var redis=org.mockito.Mockito.mock(com.yyx.ciyuantown.common.redis.RedisHotspotService.class);
        var service=new ExperienceAdjustmentService(db,accounts,levels,session,redis,factory.getBeanProvider(org.springframework.cache.CacheManager.class));
        var request=adjustment(10,UUID.randomUUID().toString());
        var first=new TransactionTemplate(manager).execute(status->service.adjust(operator,false,request));
        assertEquals(10,exp(a));assertEquals(first,adjustments.adjust(operator,false,request));
    }
}
