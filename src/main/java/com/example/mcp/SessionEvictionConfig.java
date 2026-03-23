package com.example.mcp;

import io.github.massimilianopili.mcp.channel.TransportSessionAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic eviction of stale MCP sessions.
 *
 * <p>The Spring AI KeepAliveScheduler pings all registered sessions but never
 * removes them when the ping fails. Over hours of uptime, dead sessions
 * accumulate and saturate the reactor thread pool, causing 502s.
 *
 * <p>Uses {@link TransportSessionAccessor} for session map access and ping checks.
 * Removes sessions that fail {@value #MAX_FAILED_PINGS} consecutive pings.
 */
@Component
public class SessionEvictionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionEvictionConfig.class);
    private static final int MAX_FAILED_PINGS = 3;

    private final TransportSessionAccessor sessionAccessor;
    private final ConcurrentHashMap<String, Integer> failedPingCounts = new ConcurrentHashMap<>();

    public SessionEvictionConfig(TransportSessionAccessor sessionAccessor) {
        this.sessionAccessor = sessionAccessor;
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 120_000)
    public void evictStaleSessions() {
        ConcurrentHashMap<String, ?> sessions = sessionAccessor.getSessionsMap();
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
            if (!sessionAccessor.isSessionAlive(sessionId)) {
                int count = failedPingCounts.merge(sessionId, 1, Integer::sum);
                if (count >= MAX_FAILED_PINGS) {
                    sessionAccessor.removeSession(sessionId);
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
    }
}
