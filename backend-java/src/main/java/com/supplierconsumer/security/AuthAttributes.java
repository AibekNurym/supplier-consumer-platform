package com.supplierconsumer.security;

import com.supplierconsumer.wire.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Where an authenticated principal lives for the duration of a request.
 *
 * <p>Request attributes rather than a SecurityContext, because there is no Spring Security here
 * and there are three distinct principal types that must not be confused with one another.
 */
public final class AuthAttributes {

    public static final String COMPANY = "scp.principal.company";
    public static final String CONSUMER = "scp.principal.consumer";
    public static final String CHAT = "scp.principal.chat";

    private AuthAttributes() {
    }

    public static Principals.Company company(HttpServletRequest request) {
        Object value = request.getAttribute(COMPANY);
        if (value == null) {
            throw ApiException.unauthorized("Access token required");
        }
        return (Principals.Company) value;
    }

    public static Principals.Consumer consumer(HttpServletRequest request) {
        Object value = request.getAttribute(CONSUMER);
        if (value == null) {
            throw ApiException.unauthorized("Access token required");
        }
        return (Principals.Consumer) value;
    }

    public static Principals.Chat chat(HttpServletRequest request) {
        Object value = request.getAttribute(CHAT);
        if (value == null) {
            throw ApiException.unauthorized("Missing authorization header");
        }
        return (Principals.Chat) value;
    }

    /** Reads the bearer token, mirroring {@code authHeader && authHeader.split(" ")[1]}. */
    public static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        String[] parts = header.split(" ");
        return parts.length > 1 ? parts[1] : null;
    }
}
