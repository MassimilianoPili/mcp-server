package com.example.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true", matchIfMissing = false)
public class FetchCacheService {

    private static final Logger log = LoggerFactory.getLogger(FetchCacheService.class);
    private static final String CACHE_PREFIX = "cache:fetch:";
    private static final String VALIDATE_PREFIX = "cache:validate:";

    private final ReactiveStringRedisTemplate redis;

    public FetchCacheService(
            @Qualifier("fetchCacheRedisTemplate") ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<String> getCached(String url) {
        String key = CACHE_PREFIX + sha256(url);
        return redis.opsForValue().get(key);
    }

    public Mono<Boolean> putCache(String url, String extractedJson) {
        String key = CACHE_PREFIX + sha256(url);
        return redis.opsForValue().set(key, extractedJson, FetchCacheConfig.CACHE_TTL)
                .doOnNext(ok -> log.debug("Cache put: {}", key))
                .onErrorResume(e -> {
                    log.warn("Cache put failed for '{}': {}", url, e.getMessage());
                    return Mono.just(false);
                });
    }

    public Mono<String> getValidationCached(String normalizedTitle) {
        String key = VALIDATE_PREFIX + sha256(normalizedTitle);
        return redis.opsForValue().get(key);
    }

    public Mono<Boolean> putValidationCache(String normalizedTitle, String validationJson) {
        String key = VALIDATE_PREFIX + sha256(normalizedTitle);
        return redis.opsForValue().set(key, validationJson, FetchCacheConfig.CACHE_TTL)
                .doOnNext(ok -> log.debug("Validation cache put: {}", key))
                .onErrorResume(e -> {
                    log.warn("Validation cache put failed for '{}': {}", normalizedTitle, e.getMessage());
                    return Mono.just(false);
                });
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 is always available
            return Integer.toHexString(input.hashCode());
        }
    }
}
