package com.supplierconsumer.security;

import com.supplierconsumer.repo.AuthRepository;
import com.supplierconsumer.wire.ApiException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Authenticates a request, in the three different ways this API needs.
 *
 * <p>The Node app attaches a different middleware per route, and the three are not variations of
 * one another:
 *
 * <ul>
 *   <li>the company one enforces issuer and audience;</li>
 *   <li>the consumer one enforces neither and instead requires a {@code userType} claim -- which
 *       is the only thing stopping a company token from being accepted, since both families are
 *       signed with the same secret;</li>
 *   <li>the chat one, declared inline in the route file, checks only the signature and accepts
 *       either identity.</li>
 * </ul>
 *
 * <p>These are resolved per handler rather than by URL pattern, because the URL space does not
 * split cleanly: {@code /api/orders/pending} is company-authenticated while
 * {@code /api/orders/consumer} is consumer-authenticated. Declaring the principal type a handler
 * wants reproduces the per-route middleware exactly. See {@link PrincipalArgumentResolver}.
 *
 * <p>Each failure message is reproduced verbatim; the frontend renders them and uses them as
 * translation keys.
 */
@Service
public class Authenticator {

    private static final Logger log = LoggerFactory.getLogger(Authenticator.class);

    private final JwtService jwt;
    private final AuthRepository repo;
    private final LastActiveTracker lastActive;

    public Authenticator(JwtService jwt, AuthRepository repo, LastActiveTracker lastActive) {
        this.jwt = jwt;
        this.repo = repo;
        this.lastActive = lastActive;
    }

    public Principals.Company company(HttpServletRequest request) {
        Object cached = request.getAttribute(AuthAttributes.COMPANY);
        if (cached != null) {
            return (Principals.Company) cached;
        }

        String token = AuthAttributes.bearerToken(request);
        if (token == null) {
            throw ApiException.unauthorized("Access token required");
        }

        Claims claims;
        try {
            claims = jwt.verifyCompanyAccess(token);
        } catch (Exception e) {
            log.debug("Company token rejected", e);
            throw ApiException.unauthorized("Invalid or expired token");
        }

        long userId = claims.get("userId", Number.class).longValue();
        Principals.Company user = repo.findActiveCompanyUser(userId)
                .orElseThrow(() -> ApiException.unauthorized("User not found or inactive"));

        request.setAttribute(AuthAttributes.COMPANY, user);
        lastActive.touch(userId);
        return user;
    }

    /** The platform administrator. Authority comes from the role name, not from a permission. */
    public Principals.Company admin(HttpServletRequest request) {
        Principals.Company user = company(request);
        if (!user.isAdmin()) {
            throw ApiException.forbidden("Access denied. Admin privileges required.");
        }
        return user;
    }

    public Principals.Consumer consumer(HttpServletRequest request) {
        return consumerOrEmpty(request)
                .orElseThrow(() -> ApiException.unauthorized("Access token required"));
    }

    /**
     * The optional variant, used by the public catalog listing. It never fails: an absent or
     * unusable token simply means an anonymous caller.
     */
    public Optional<Principals.Consumer> optionalConsumer(HttpServletRequest request) {
        try {
            return consumerOrEmpty(request);
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    private Optional<Principals.Consumer> consumerOrEmpty(HttpServletRequest request) {
        Object cached = request.getAttribute(AuthAttributes.CONSUMER);
        if (cached != null) {
            return Optional.of((Principals.Consumer) cached);
        }

        String token = AuthAttributes.bearerToken(request);
        if (token == null) {
            return Optional.empty();
        }

        Claims claims;
        try {
            claims = jwt.verifyConsumerAccess(token);
            if (!"consumer".equals(claims.get("userType", String.class))) {
                throw new IllegalArgumentException("Invalid token type");
            }
        } catch (Exception e) {
            log.debug("Consumer token rejected", e);
            throw ApiException.unauthorized("Invalid access token");
        }

        long consumerId = claims.get("consumerId", Number.class).longValue();
        Principals.Consumer consumer = repo.findActiveConsumer(consumerId)
                .orElseThrow(() -> ApiException.unauthorized("Consumer not found or inactive"));

        request.setAttribute(AuthAttributes.CONSUMER, consumer);
        return Optional.of(consumer);
    }

    /**
     * The chat variant, which accepts either identity and reports which one it found.
     *
     * <p>It picks the branch by looking for a {@code consumerId} claim and falling back to
     * {@code userId}. Note the asymmetry inherited from the original: the company branch requires
     * an active account, the consumer branch does not.
     */
    public Principals.Chat chat(HttpServletRequest request) {
        Object cached = request.getAttribute(AuthAttributes.CHAT);
        if (cached != null) {
            return (Principals.Chat) cached;
        }

        String token = AuthAttributes.bearerToken(request);
        if (token == null) {
            throw ApiException.unauthorized("Missing authorization header");
        }

        Claims claims;
        try {
            claims = jwt.verifyAnyAccess(token);
        } catch (Exception e) {
            log.debug("Chat token rejected", e);
            throw ApiException.unauthorized("Invalid token");
        }

        Number consumerId = claims.get("consumerId", Number.class);
        Number userId = claims.get("userId", Number.class);

        Principals.Chat principal;
        if (consumerId != null) {
            principal = repo.findChatConsumer(consumerId.longValue())
                    .orElseThrow(() -> ApiException.unauthorized("Consumer not found"));
        } else if (userId != null) {
            principal = repo.findChatCompanyUser(userId.longValue())
                    .orElseThrow(() -> ApiException.unauthorized("User not found"));
        } else {
            throw ApiException.unauthorized("Invalid token");
        }

        request.setAttribute(AuthAttributes.CHAT, principal);
        return principal;
    }
}
