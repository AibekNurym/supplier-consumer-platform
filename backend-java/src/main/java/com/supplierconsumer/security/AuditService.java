package com.supplierconsumer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Writes the audit trail.
 *
 * <p>Failures here are swallowed on purpose, matching the original: a logout by a user whose row
 * has already been deleted still has to succeed, and an audit write must never be the reason a
 * business operation fails.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcClient db;
    private final ObjectMapper mapper;

    public AuditService(JdbcClient db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public void log(Long userId, String action, String resource, Integer resourceId,
                    Map<String, ?> details, HttpServletRequest request) {
        try {
            db.sql("""
                            INSERT INTO audit_log
                                (user_id, action, resource, resource_id, details, ip_address, user_agent)
                            VALUES (:userId, :action, :resource, :resourceId,
                                    CAST(:details AS jsonb), CAST(:ip AS inet), :userAgent)
                            """)
                    .param("userId", userId)
                    .param("action", action)
                    .param("resource", resource)
                    .param("resourceId", resourceId)
                    .param("details", details == null ? null : mapper.writeValueAsString(details))
                    .param("ip", clientIp(request))
                    .param("userAgent", request == null ? null : request.getHeader("User-Agent"))
                    .update();
        } catch (Exception e) {
            log.warn("Failed to write audit entry for action {}: {}", action, e.getMessage());
        }
    }

    /**
     * Postgres rejects a malformed value for an {@code inet} column, so anything that is not a
     * plain address is dropped rather than risking the insert. IPv6-mapped loopback is normalised
     * because that is what a local request arrives as.
     */
    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();

        if (ip == null || ip.isBlank()) {
            return null;
        }
        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            return "::1";
        }
        return ip;
    }
}
