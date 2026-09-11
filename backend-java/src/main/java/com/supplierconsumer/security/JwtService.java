package com.supplierconsumer.security;

import com.supplierconsumer.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mints and validates both families of token.
 *
 * <p>The platform has two separate identities -- company staff in {@code users} and buyers in
 * {@code consumer_users} -- and although they are signed with the same secret, their tokens are
 * shaped differently:
 *
 * <ul>
 *   <li><strong>Company</strong>: claims {@code userId, email, roleId, roleName}, signed with
 *       issuer {@code pern-stack-app} and audience {@code pern-stack-users}.</li>
 *   <li><strong>Consumer</strong>: claims {@code consumerId, email, userType: "consumer"}, with
 *       no issuer and no audience.</li>
 * </ul>
 *
 * <p>Because the secret is shared, the {@code userType} claim is the only thing stopping a company
 * token being accepted on a consumer route -- the consumer filter checks it explicitly. In the
 * other direction the issuer and audience requirements do the work, since a consumer token carries
 * neither.
 *
 * <p>The key is the raw UTF-8 bytes of the configured secret, which is what {@code jsonwebtoken}
 * used, so tokens already issued by the Node service still validate here.
 */
@Service
public class JwtService {

    private final SecretKey accessKey;
    private final SecretKey refreshKey;
    private final String issuer;
    private final String audience;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(AppProperties props) {
        AppProperties.Jwt jwt = props.jwt();
        this.accessKey = keyFor(jwt.secret(), "app.jwt.secret");
        this.refreshKey = keyFor(jwt.refreshSecret(), "app.jwt.refresh-secret");
        this.issuer = jwt.issuer();
        this.audience = jwt.audience();
        this.accessTtl = jwt.accessTtl();
        this.refreshTtl = jwt.refreshTtl();
    }

    private static SecretKey keyFor(String secret, String property) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(property + " must be set; there is deliberately no default");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    property + " must be at least 32 bytes for HS256; got " + bytes.length);
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    // -- company tokens ----------------------------------------------------------------

    public String signCompanyAccess(long userId, String email, int roleId, String roleName) {
        return Jwts.builder()
                .claims(companyClaims(userId, email, roleId, roleName))
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(accessTtl)))
                .signWith(accessKey)
                .compact();
    }

    public String signCompanyRefresh(long userId, String email, int roleId, String roleName) {
        return Jwts.builder()
                .claims(companyClaims(userId, email, roleId, roleName))
                .id(newTokenId())
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(refreshTtl)))
                .signWith(refreshKey)
                .compact();
    }

    private Map<String, Object> companyClaims(long userId, String email, int roleId, String roleName) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("userId", userId);
        claims.put("email", email);
        claims.put("roleId", roleId);
        claims.put("roleName", roleName);
        return claims;
    }

    /** Validates a company access token, enforcing issuer and audience as the Node version did. */
    public Claims verifyCompanyAccess(String token) {
        return Jwts.parser()
                .verifyWith(accessKey)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // -- consumer tokens ---------------------------------------------------------------

    public String signConsumerAccess(long consumerId, String email) {
        return Jwts.builder()
                .claims(consumerClaims(consumerId, email))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(accessTtl)))
                .signWith(accessKey)
                .compact();
    }

    public String signConsumerRefresh(long consumerId, String email) {
        return Jwts.builder()
                .claims(consumerClaims(consumerId, email))
                .id(newTokenId())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(refreshTtl)))
                .signWith(refreshKey)
                .compact();
    }

    private Map<String, Object> consumerClaims(long consumerId, String email) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("consumerId", consumerId);
        claims.put("email", email);
        claims.put("userType", "consumer");
        return claims;
    }

    /**
     * Validates a consumer access token. No issuer or audience requirement, because the Node
     * consumer path signs without them -- requiring them here would reject every valid token.
     */
    public Claims verifyConsumerAccess(String token) {
        return Jwts.parser()
                .verifyWith(accessKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Validates a token from either family, used only by the chat routes, whose middleware
     * accepts both and decides which identity it holds by looking at the claims.
     */
    public Claims verifyAnyAccess(String token) {
        return Jwts.parser()
                .verifyWith(accessKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            verifyAnyAccess(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Gives each refresh token a unique {@code jti}.
     *
     * <p>Without it, two sign-ins for the same account within the same second produce byte-identical
     * tokens, because every claim including {@code iat} matches. In the original that means the two
     * sessions literally share a refresh token, so revoking either kills both. A random id makes
     * each session's token distinct, which is also what lets the digest column carry a unique index.
     *
     * <p>Access tokens are left alone: they are never stored, and clients treat them as opaque.
     */
    private String newTokenId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Refresh tokens are persisted as a SHA-256 hex digest rather than in full, so a database
     * leak does not hand over usable tokens.
     */
    public String hashRefreshToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }
}
