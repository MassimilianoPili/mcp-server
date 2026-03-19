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
public class WebFetchChunkConfig {

    private static final Logger log = LoggerFactory.getLogger(WebFetchChunkConfig.class);

    static final int CHUNK_DB = 6;
    static final int CHUNK_SIZE = 6 * 1024; // 6KB
    static final Duration CHUNK_TTL = Duration.ofMinutes(10);

    @Value("${mcp.redis.url:redis://redis:6379}")
    private String redisUrl;

    @Bean(name = "fetchChunkConnectionFactory")
    public LettuceConnectionFactory fetchChunkConnectionFactory() {
        try {
            URI uri = URI.create(redisUrl);
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
            config.setDatabase(CHUNK_DB);
            if (uri.getUserInfo() != null) {
                String[] parts = uri.getUserInfo().split(":", 2);
                if (parts.length == 2) config.setPassword(parts[1]);
            }
            log.info("Redis fetch-chunk: {}:{} db={}", uri.getHost(), uri.getPort(), CHUNK_DB);
            LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            return factory;
        } catch (Exception e) {
            throw new IllegalStateException("Redis fetch-chunk config non valida: " + e.getMessage(), e);
        }
    }

    @Bean(name = "fetchChunkRedisTemplate")
    public ReactiveStringRedisTemplate fetchChunkRedisTemplate(
            @Qualifier("fetchChunkConnectionFactory") ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }
}
