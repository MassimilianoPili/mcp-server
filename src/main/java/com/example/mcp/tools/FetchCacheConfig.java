package com.example.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.net.URI;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true", matchIfMissing = false)
public class FetchCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(FetchCacheConfig.class);

    static final int CACHE_DB = 8;
    static final Duration CACHE_TTL = Duration.ofHours(24);
    static final int INGEST_BATCH_SIZE = 50;

    @Value("${mcp.redis.url:redis://redis:6379}")
    private String redisUrl;

    @Bean(name = "fetchCacheConnectionFactory")
    public LettuceConnectionFactory fetchCacheConnectionFactory() {
        try {
            URI uri = URI.create(redisUrl);
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
            config.setDatabase(CACHE_DB);
            if (uri.getUserInfo() != null) {
                String[] parts = uri.getUserInfo().split(":", 2);
                if (parts.length == 2) config.setPassword(parts[1]);
            }
            log.info("Redis fetch-cache: {}:{} db={}", uri.getHost(), uri.getPort(), CACHE_DB);
            LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            return factory;
        } catch (Exception e) {
            throw new IllegalStateException("Redis fetch-cache config non valida: " + e.getMessage(), e);
        }
    }

    @Bean(name = "fetchCacheRedisTemplate")
    public ReactiveStringRedisTemplate fetchCacheRedisTemplate(
            @Qualifier("fetchCacheConnectionFactory") ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }
}
