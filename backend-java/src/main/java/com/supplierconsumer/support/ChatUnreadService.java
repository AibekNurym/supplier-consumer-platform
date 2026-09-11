package com.supplierconsumer.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-conversation unread counters, held in Redis.
 *
 * <p>Two hashes, one per side: {@code chat:unread:consumer:{id}} with a field per company, and
 * {@code chat:unread:company:{id}} with a field per consumer. Incrementing is driven by the
 * sender -- a message from a buyer bumps the supplier's counter -- while clearing is driven by the
 * reader and zeroes the whole conversation at once, since there is no per-message read tracking.
 *
 * <p>Redis is optional. When it is unreachable the counters fall back to an in-process map and the
 * application keeps working, which is what the original does; the numbers are then per-instance and
 * lost on restart, but the conversation list still renders. The database also keeps a
 * {@code read_at} column, and the conversation query prefers whichever value Redis holds, so these
 * two sources can legitimately disagree.
 */
@Service
public class ChatUnreadService {

    private static final Logger log = LoggerFactory.getLogger(ChatUnreadService.class);

    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;

    private final Map<String, Map<String, Integer>> fallback = new ConcurrentHashMap<>();
    private volatile boolean redisUsable = true;

    public ChatUnreadService(StringRedisTemplate redis, ApplicationEventPublisher events) {
        this.redis = redis;
        this.events = events;
    }

    private static String consumerKey(long consumerId) {
        return "chat:unread:consumer:" + consumerId;
    }

    private static String companyKey(long companyId) {
        return "chat:unread:company:" + companyId;
    }

    /** The message went to whichever side did not send it. */
    public void increment(long consumerId, long companyId, String senderType) {
        if ("consumer".equals(senderType)) {
            bump(companyKey(companyId), "consumer:" + consumerId, "company", consumerId, companyId);
        } else {
            bump(consumerKey(consumerId), "company:" + companyId, "consumer", consumerId, companyId);
        }
    }

    /** Marking read clears the entire conversation for that reader. */
    public void clear(long consumerId, long companyId, String readerType) {
        if ("consumer".equals(readerType)) {
            drop(consumerKey(consumerId), "company:" + companyId, "consumer", consumerId, companyId);
        } else {
            drop(companyKey(companyId), "consumer:" + consumerId, "company", consumerId, companyId);
        }
    }

    public Map<String, Integer> consumerSnapshot(long consumerId) {
        return snapshot(consumerKey(consumerId));
    }

    public Map<String, Integer> companySnapshot(long companyId) {
        return snapshot(companyKey(companyId));
    }

    /** The count for one conversation, or null when nothing is recorded. */
    public Integer countFor(String targetType, long consumerId, long companyId) {
        Map<String, Integer> counts = "consumer".equals(targetType)
                ? consumerSnapshot(consumerId)
                : companySnapshot(companyId);
        return counts.get("consumer".equals(targetType)
                ? "company:" + companyId
                : "consumer:" + consumerId);
    }

    private void bump(String key, String field, String targetType, long consumerId, long companyId) {
        int updated;
        if (redisUsable) {
            try {
                updated = redis.opsForHash().increment(key, field, 1L).intValue();
            } catch (Exception e) {
                degrade(e);
                updated = bumpFallback(key, field);
            }
        } else {
            updated = bumpFallback(key, field);
        }
        publish(targetType, consumerId, companyId, field, updated, total(key));
    }

    private void drop(String key, String field, String targetType, long consumerId, long companyId) {
        if (redisUsable) {
            try {
                redis.opsForHash().delete(key, field);
            } catch (Exception e) {
                degrade(e);
                dropFallback(key, field);
            }
        } else {
            dropFallback(key, field);
        }
        publish(targetType, consumerId, companyId, field, 0, total(key));
    }

    private Map<String, Integer> snapshot(String key) {
        if (redisUsable) {
            try {
                Map<Object, Object> raw = redis.opsForHash().entries(key);
                Map<String, Integer> out = new LinkedHashMap<>();
                raw.forEach((k, v) -> out.put(String.valueOf(k), parse(v)));
                return out;
            } catch (Exception e) {
                degrade(e);
            }
        }
        return new LinkedHashMap<>(fallback.getOrDefault(key, Map.of()));
    }

    private int total(String key) {
        return snapshot(key).values().stream().mapToInt(Integer::intValue).sum();
    }

    private int bumpFallback(String key, String field) {
        return fallback.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .merge(field, 1, Integer::sum);
    }

    private void dropFallback(String key, String field) {
        Map<String, Integer> counts = fallback.get(key);
        if (counts != null) {
            counts.remove(field);
        }
    }

    /**
     * Switches to the in-process store for good once Redis has failed.
     *
     * <p>Flipping permanently rather than retrying per call avoids the split-brain the original
     * has, where a connected-but-erroring Redis makes some operations land in one store and some in
     * the other.
     */
    private void degrade(Exception e) {
        if (redisUsable) {
            redisUsable = false;
            log.warn("Redis unavailable ({}). Falling back to in-process unread counters; "
                    + "counts are now per-instance and reset on restart.", e.getMessage());
        }
    }

    private static int parse(Object value) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void publish(String targetType, long consumerId, long companyId, String field,
                         int unreadCount, int totalUnread) {
        events.publishEvent(new UnreadChanged(targetType, consumerId, companyId, field,
                unreadCount, totalUnread));
    }

    /** Consumed by the realtime layer, so this service knows nothing about sockets. */
    public record UnreadChanged(String type, long consumerId, long companyId, String field,
                                int unreadCount, int totalUnread) {
    }
}
