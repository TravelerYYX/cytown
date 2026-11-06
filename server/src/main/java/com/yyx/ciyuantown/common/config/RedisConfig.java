package com.yyx.ciyuantown.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 基础配置（docs/redis-architecture-redesign.md 落地第 1 步）。
 *
 * <p>由 feature flag {@code ciyuantown.redis.enabled}（环境变量 REDIS_ENABLED）控制：
 * <ul>
 *   <li>开启：以同名 Bean 覆盖 Boot 默认的 {@code redisTemplate}（避免误用其 JDK 序列化的
 *       {@code RedisTemplate<Object,Object>}），并提供 {@link RedisCacheManager}（Key 统一
 *       {@code ct:} 前缀、JSON 序列化、默认 TTL 5min、空值不缓存），激活 {@code @Cacheable} 系列注解；</li>
 *   <li>关闭（仅故障回滚）：不产生任何上述 Bean，业务代码全部走现有 DB 实现，可安全回退。</li>
 * </ul>
 *
 * <p>说明：Boot 还会自动装配 {@code StringRedisTemplate}（同为 {@code RedisTemplate<String,String>}）；
 * 本 Bean 标注 {@code @Primary}，按类型注入 {@code RedisTemplate<String,String>} 时优先命中本 Bean，
 * 不产生二义。业务代码优先直接注入 {@code StringRedisTemplate} 或本 Bean。</p>
 *
 * <p>Lettuce 连接惰性建立：开关开启但 Redis 未启动不会导致应用启动失败，仅在实际读写时抛错；
 * 后续各业务步骤落地时须按方案 §7 统一 try-catch / 降级抽象兜底，避免缓存故障拖垮主链路。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ciyuantown.redis", name = "enabled", havingValue = "true")
@EnableCaching
@Slf4j
public class RedisConfig implements CachingConfigurer {

    /** 统一 Key 前缀（设计文档 §2：ct:） */
    private static final String KEY_PREFIX = "ct:";

    /** 默认缓存 TTL：5 分钟；各业务缓存的 TTL 在后续步骤按文档 §2 覆盖 */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    /**
     * 缓存 Value 专用的 Jackson ObjectMapper。
     *
     * <p>{@code RedisSerializer.json()} 内部是 {@code new GenericJackson2JsonRedisSerializer()}，
     * 其自建 ObjectMapper <b>不注册</b> {@link JavaTimeModule}，遇到实体里的 {@code java.time.*}
     * 字段（如 {@code PublishRule.updateTime}）会在序列化阶段抛 {@code InvalidDefinitionException}，
     * 被下方 {@link #errorHandler()} 吞掉后表现为「缓存静默失效、每次都回源 MySQL」。
     * 这里显式注册 JSR-310 模块补上该缺口；{@code jackson-datatype-jsr310} 由
     * {@code spring-boot-starter-web -> spring-boot-starter-json} 传递引入，无需额外加依赖。
     */
    private static final ObjectMapper CACHE_OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // 输出 ISO-8601 字符串（"2026-10-10T19:29:19"）而非时间戳数组，便于人工排查 Redis 中的值
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Bean
    @Primary
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Key / Value 均走字符串序列化：业务侧自行 JSON 序列化，规避泛型类型探测与 JavaTime 序列化差异
        StringRedisSerializer string = new StringRedisSerializer();
        template.setKeySerializer(string);
        template.setHashKeySerializer(string);
        template.setValueSerializer(string);
        template.setHashValueSerializer(string);
        return template;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(KEY_PREFIX)
                .entryTtl(DEFAULT_TTL)
                // 空值不落缓存，避免把「不存在」缓存成常态；防穿透由业务侧短 TTL 空缓存/布隆处理
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                // 不用 RedisSerializer.json()：它内部的 ObjectMapper 缺 JavaTimeModule，
                // 且我们也无法注入自定义 mapper。改用 builder 注入带 JSR-310 的 mapper，
                // 并保持 defaultTyping(true) 写入 @class 类型信息 —— 否则 Page/泛型集合
                // 反序列化会退化成 LinkedHashMap，读缓存时 ClassCastException。
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(GenericJackson2JsonRedisSerializer.builder()
                                .objectMapper(CACHE_OBJECT_MAPPER)
                                .defaultTyping(true)
                                .build()));
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(
                        "post-list", defaults.entryTtl(Duration.ofSeconds(30)),
                        "post-detail", defaults.entryTtl(Duration.ofMinutes(2)),
                        "notice", defaults.entryTtl(Duration.ofMinutes(2)),
                        "home-config", defaults.entryTtl(Duration.ofMinutes(5)),
                        "publish-config", defaults.entryTtl(Duration.ofMinutes(5))))
                .build();
    }

    /** 缓存读写异常时全部降级为 miss，业务方法继续从 MySQL 返回结果。 */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override public void handleCacheGetError(RuntimeException e, Cache cache, Object key) { warn("GET",e,cache,key); }
            @Override public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) { warn("PUT",e,cache,key); }
            @Override public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) { warn("EVICT",e,cache,key); }
            @Override public void handleCacheClearError(RuntimeException e, Cache cache) { warn("CLEAR",e,cache,null); }
            /**
             * 降级日志。注意：本处理器只能看到「缓存操作抛错」，无法判断根因是连接层（Redis 挂了）还是
             * 序列化层（如对象含 JavaTime 字段且 mapper 未注册 JSR-310 模块），二者都会走到这里。
             * 故日志必须带上操作类型与异常类名，否则「Redis 缓存不可用」会把两类问题混为一谈、误导排查。
             */
            private void warn(String op, RuntimeException e, Cache cache, Object key){
                log.warn("Redis 缓存{}操作失败，已降级回源 MySQL: cache={}, key={}, type={}, error={}",
                        op, cache.getName(), key, e.getClass().getSimpleName(), e.getMessage());
            }
        };
    }
}
