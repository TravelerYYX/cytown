package com.yyx.ciyuantown;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisConfig 条件装配验证（docs/redis-architecture-redesign.md 落地第 1 步）。
 *
 * <p>无需真实 Redis/MySQL：Lettuce 惰性连接，仅装配不建连。
 * 断言 feature flag 关闭（默认）时不产生 Redis Bean、开启时才装配，保证可回退、不影响现有功能。
 */
class RedisConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(com.yyx.ciyuantown.common.config.RedisConfig.class, TestFactoryConfig.class);

    @Test
    void disabledByDefault_createsNoRedisBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RedisTemplate.class);
            assertThat(context).doesNotHaveBean(CacheManager.class);
            assertThat(context).doesNotHaveBean(CacheInterceptor.class);
        });
    }

    @Test
    void enabled_createsStringRedisTemplateAndJsonCacheManager() {
        runner.withPropertyValues("ciyuantown.redis.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(RedisTemplate.class);
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context).hasSingleBean(RedisCacheManager.class);
            // @EnableCaching 随开关生效
            assertThat(context).hasSingleBean(CacheInterceptor.class);

            RedisTemplate<?, ?> template = context.getBean(RedisTemplate.class);
            assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
            assertThat(template.getValueSerializer()).isInstanceOf(StringRedisSerializer.class);
        });
    }

    /** 仅提供连接工厂便于装配；不会真正连接 127.0.0.1:6379 */
    @Configuration(proxyBeanMethods = false)
    static class TestFactoryConfig {
        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory("127.0.0.1", 6379);
        }
    }
}
