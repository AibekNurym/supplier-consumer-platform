package com.supplierconsumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Wire wire,
        Jwt jwt,
        Uploads uploads,
        SocketIo socketio,
        RateLimit rateLimit) {

    public record Wire(@DefaultValue("Asia/Almaty") String timezone) {
        public ZoneId zone() {
            return ZoneId.of(timezone);
        }
    }

    /**
     * secret and refreshSecret have no defaults. The Node code falls back to a literal that
     * is committed to a public repository, which means anyone can mint a valid token for any
     * account; failing startup is the only honest behaviour.
     */
    public record Jwt(
            String secret,
            String refreshSecret,
            @DefaultValue("pern-stack-app") String issuer,
            @DefaultValue("pern-stack-users") String audience,
            @DefaultValue("15m") Duration accessTtl,
            @DefaultValue("7d") Duration refreshTtl) {
    }

    public record Uploads(@DefaultValue("./uploads") String dir) {
    }

    public record SocketIo(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.0.0.0") String host,
            @DefaultValue("3001") int port) {
    }

    public record RateLimit(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("5") int authPerWindow,
            @DefaultValue("3") int strictPerWindow,
            @DefaultValue("15m") Duration window) {
    }
}
