package com.supplierconsumer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplierconsumer.wire.ApiResponse;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting for the sign-in and registration endpoints.
 *
 * <p>The original declares two express-rate-limit limiters -- five requests per fifteen minutes on
 * the ordinary auth routes, three on the sensitive ones -- and then never attaches them, with a
 * comment saying it was disabled for development. So nothing is rate limited today, including
 * login, which leaves password guessing entirely unthrottled.
 *
 * <p>This implements what the source clearly intended, but is <strong>off by default</strong> so
 * that out-of-the-box behaviour still matches the original. Setting
 * {@code app.rate-limit.enabled=true} turns it on.
 *
 * <p>Counters are per instance. For a single deployment that is enough; behind several instances
 * the effective limit multiplies, and a shared store would be needed.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true")
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** The endpoints the original's stricter limiter was written for. */
    private static final Set<String> STRICT = Set.of(
            "/api/auth/refresh-token",
            "/api/auth/change-password",
            "/api/consumer/auth/refresh-token");

    private static final Set<String> STANDARD = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/consumer/auth/login",
            "/api/consumer/auth/register",
            "/api/company/register");

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        log.info("Rate limiting enabled: {}/{} per {} on auth endpoints",
                props.rateLimit().authPerWindow(), props.rateLimit().strictPerWindow(),
                props.rateLimit().window());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean strict = STRICT.contains(path);

        if (!strict && !STANDARD.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        Bucket bucket = buckets.computeIfAbsent(clientIp(request) + "|" + path,
                key -> newBucket(strict));

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ApiResponse.fail()
                .message("Too many authentication attempts, please try again later"));
    }

    private Bucket newBucket(boolean strict) {
        Duration window = props.rateLimit().window();
        int capacity = strict ? props.rateLimit().strictPerWindow() : props.rateLimit().authPerWindow();
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(capacity)
                        .refillIntervally(capacity, window).build())
                .build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
