package com.yyx.ciyuantown;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真机 Redis 连通性联调（docs/redis-architecture-redesign.md 落地第 1 步收尾）。
 *
 * <p>前提：Redis 功能开关生效（OS 环境变量或 .env 的 {@code REDIS_ENABLED=true}，即属性
 * {@code ciyuantown.redis.enabled=true}）且 127.0.0.1:6379 有实例
 * （如 {@code docker run -d --name ciyuantown-redis -p 6379:6379 redis:7-alpine}）。
 * 不满足前提时自动跳过，因此无 Redis 的机器上常规回归不受影响。
 * 测试自行清理探针 Key，不污染业务数据。
 */
@SpringBootTest
class RedisConnectivityTest {

    @Autowired
    private Environment env;

    /** 与生产开关同源（OS 环境变量 / .env / yml 默认），避免测试门控与运行态不一致 */
    private boolean redisEnabled() {
        return Boolean.parseBoolean(env.getProperty("ciyuantown.redis.enabled", "false"));
    }

    @Autowired(required = false)
    private RedisTemplate<String, String> redis;

    @Autowired(required = false)
    private RedisCacheManager cacheManager;

    @Test
    void templateRoundTripOnRealRedis() {
        assumeTrue(redisEnabled(), "需 ciyuantown.redis.enabled=true 且本地 6379 有 Redis 实例");
        assertThat(redis).as("ciyuantown.redis.enabled=true 时应装配 RedisTemplate").isNotNull();
        String key = "ct:probe:it:" + System.nanoTime();
        try {
            redis.opsForValue().set(key, "hello");
            assertThat(redis.opsForValue().get(key)).isEqualTo("hello");
            assertThat(redis.hasKey(key)).isTrue();
        } finally {
            redis.delete(key);
        }
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void cacheManagerReadWriteOnRealRedis() {
        assumeTrue(redisEnabled(), "需 ciyuantown.redis.enabled=true 且本地 6379 有 Redis 实例");
        assertThat(cacheManager).as("ciyuantown.redis.enabled=true 时应装配 RedisCacheManager").isNotNull();
        var cache = cacheManager.getCache("probe");
        assertThat(cache).isNotNull();
        try {
            cache.put("k1", "v1");
            assertThat(cache.get("k1", String.class)).isEqualTo("v1");
            cache.put("k2", "v2");
            assertThat(cacheManager.getCache("probe").get("k2", String.class)).isEqualTo("v2");
        } finally {
            cache.evict("k1");
            cache.evict("k2");
        }
        assertThat(cache.get("k1", String.class)).isNull();
    }
}
