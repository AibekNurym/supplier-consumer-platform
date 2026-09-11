package com.supplierconsumer.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Reports Redis status without letting it mark the whole application down.
 *
 * <p>Redis holds only chat unread counters here, and the service falls back to an in-process store
 * when it is unreachable -- the application keeps serving every endpoint. Spring's built-in
 * indicator would report DOWN and, through the composite, fail the overall health check, so a load
 * balancer would pull a perfectly functional instance. This one stays UP and puts the real state in
 * the details instead.
 */
@Configuration
public class RedisHealthConfig {

    @Bean
    @Primary
    public HealthIndicator redisHealthIndicator(StringRedisTemplate redis) {
        return () -> {
            try {
                redis.execute(connection -> connection.ping(), true);
                return Health.up().withDetail("redis", "connected").build();
            } catch (Exception e) {
                return Health.up()
                        .withDetail("redis", "unavailable")
                        .withDetail("impact", "chat unread counters are using the in-process "
                                + "fallback; they are per-instance and reset on restart")
                        .build();
            }
        };
    }
}
