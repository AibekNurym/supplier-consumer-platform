package com.supplierconsumer.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps {@code users.last_active} current, which is how the console shows who is online.
 *
 * <p>The Node middleware issues this UPDATE on every authenticated request, so each API call costs
 * three database round trips and a row write -- on an unindexed column, for a value whose only
 * consumer is a coarse "recently seen" display. Here the write is throttled to at most once a
 * minute per user and moved off the request thread, which preserves what the value is used for
 * while removing the per-request cost.
 *
 * <p>The map is per-process. With several instances behind a load balancer a user could be written
 * once per minute per instance, which is still far below the current rate and harmless for a
 * timestamp that is only ever read approximately.
 */
@Component
public class LastActiveTracker {

    private static final Duration INTERVAL = Duration.ofMinutes(1);

    private final JdbcClient db;
    private final Map<Long, Instant> lastWritten = new ConcurrentHashMap<>();

    public LastActiveTracker(JdbcClient db) {
        this.db = db;
    }

    public void touch(long userId) {
        Instant now = Instant.now();
        Instant previous = lastWritten.get(userId);
        if (previous != null && Duration.between(previous, now).compareTo(INTERVAL) < 0) {
            return;
        }
        lastWritten.put(userId, now);
        write(userId);
    }

    @Async
    void write(long userId) {
        try {
            db.sql("UPDATE users SET last_active = CURRENT_TIMESTAMP WHERE id = :id")
                    .param("id", userId)
                    .update();
        } catch (RuntimeException e) {
            // Presence tracking must never be the reason a request fails.
            lastWritten.remove(userId);
        }
    }
}
