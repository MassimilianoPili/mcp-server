package com.example.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic eviction of stale MCP sessions.
 *
 * The Spring AI KeepAliveScheduler pings all registered sessions but never
 * removes them when the ping fails. Over hours of uptime, dead sessions
 * accumulate and saturate the reactor thread pool, causing 502s.
 *
 * This bean accesses the transport provider's internal session map via
 * reflection and removes sessions that fail 3 consecutive pings.
 * Works with both SSE and Streamable HTTP transports.
 */
@Component
public class SessionEvictionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionEvictionConfig.class);
    private static final int MAX_FAILED_PINGS = 3;
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(3);

    private Object mcpTransportProvider;
    private final ConcurrentHashMap<String, Integer> failedPingCounts = new ConcurrentHashMap<>();
    private volatile Field sessionsField;
    private volatile boolean reflectionFailed;

    @Autowired
    public void init(org.springframework.context.ApplicationContext ctx) {
        // Find the transport provider bean by type name
        // Works for both WebFluxSseServerTransportProvider and WebFluxStreamableServerTransportProvider
        for (String name : ctx.getBeanDefinitionNames()) {
            try {
                Object bean = ctx.getBean(name);
                String className = bean.getClass().getSimpleName();
                if (className.contains("TransportProvider") && className.contains("WebFlux")) {
                    this.mcpTransportProvider = bean;
                    log.info("Session eviction: found transport provider {}", className);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        log.warn("Session eviction: no transport provider found — eviction disabled");
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 120_000)
    public void evictStaleSessions() {
        if (mcpTransportProvider == null || reflectionFailed) return;

        try {
            ConcurrentHashMap<String, ?> sessions = getSessionsMap();
            if (sessions == null) return;

            int before = sessions.size();
            if (before == 0) {
                failedPingCounts.clear();
                return;
            }

            Set<String> activeIds = new HashSet<>(sessions.keySet());
            failedPingCounts.keySet().retainAll(activeIds);
            int evicted = 0;

            for (String sessionId : activeIds) {
                Object session = sessions.get(sessionId);
                if (session == null) continue;

                if (isSessionStale(session, sessionId)) {
                    int count = failedPingCounts.merge(sessionId, 1, Integer::sum);
                    if (count >= MAX_FAILED_PINGS) {
                        sessions.remove(sessionId);
                        failedPingCounts.remove(sessionId);
                        evicted++;
                        String shortId = sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
                        log.info("Evicted stale session {} after {} failed pings", shortId, count);
                    }
                } else {
                    failedPingCounts.remove(sessionId);
                }
            }

            if (evicted > 0) {
                log.info("Session eviction: {} -> {} (removed {})", before, sessions.size(), evicted);
            }
        } catch (Exception e) {
            log.warn("Session eviction cycle failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, ?> getSessionsMap() {
        if (sessionsField == null) {
            try {
                sessionsField = mcpTransportProvider.getClass().getDeclaredField("sessions");
                sessionsField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                log.warn("Session eviction: 'sessions' field not found in {} — disabling",
                        mcpTransportProvider.getClass().getSimpleName());
                reflectionFailed = true;
                return null;
            }
        }
        try {
            return (ConcurrentHashMap<String, ?>) sessionsField.get(mcpTransportProvider);
        } catch (Exception e) {
            log.warn("Session eviction: cannot access sessions map: {}", e.getMessage());
            return null;
        }
    }

    private boolean isSessionStale(Object session, String sessionId) {
        try {
            // McpSession interface has sendPing() returning Mono<Object>
            var sendPing = session.getClass().getMethod("sendPing");
            @SuppressWarnings("unchecked")
            Mono<Object> pingResult = (Mono<Object>) sendPing.invoke(session);
            pingResult.block(PING_TIMEOUT);
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
