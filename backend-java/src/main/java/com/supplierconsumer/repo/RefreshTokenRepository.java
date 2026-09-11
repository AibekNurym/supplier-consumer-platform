package com.supplierconsumer.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The two refresh-token tables, which mirror each other for the two identities.
 *
 * <p>Tokens are stored as a SHA-256 digest rather than in full. One asymmetry is carried over
 * deliberately: the company refresh endpoint hands the same refresh token back and leaves the
 * stored row alone, while the consumer endpoint revokes the old digest and stores a new one.
 * Rotating the company side would be an improvement but would change what clients receive.
 */
@Repository
public class RefreshTokenRepository {

    private final JdbcClient db;

    public RefreshTokenRepository(JdbcClient db) {
        this.db = db;
    }

    // -- company ------------------------------------------------------------------------

    public void storeForUser(long userId, String tokenHash, LocalDateTime expiresAt) {
        db.sql("""
                        INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
                        VALUES (:userId, :hash, :expiresAt)
                        """)
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("expiresAt", expiresAt)
                .update();
    }

    /**
     * Resolves a stored company refresh token to its owner.
     *
     * <p>Only the stored digest is checked, not the JWT signature -- that is what the Node
     * refresh path does, and since the row is what authorises the refresh, the digest lookup is
     * the real gate.
     */
    public Optional<StoredUserToken> findValidUserToken(String tokenHash) {
        return db.sql("""
                        SELECT rt.id, rt.user_id, u.email, u.role_id, r.name AS role_name
                        FROM refresh_tokens rt
                        JOIN users u ON rt.user_id = u.id
                        JOIN roles r ON u.role_id = r.id
                        WHERE rt.token_hash = :hash
                          AND rt.is_revoked = false
                          AND rt.expires_at > NOW()
                          AND u.is_active = true
                        """)
                .param("hash", tokenHash)
                .query((rs, n) -> new StoredUserToken(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("email"),
                        rs.getInt("role_id"),
                        rs.getString("role_name")))
                .optional();
    }

    public void revokeUserToken(String tokenHash) {
        db.sql("UPDATE refresh_tokens SET is_revoked = true WHERE token_hash = :hash")
                .param("hash", tokenHash)
                .update();
    }

    // -- consumer -----------------------------------------------------------------------

    public void storeForConsumer(long consumerId, String tokenHash, LocalDateTime expiresAt) {
        db.sql("""
                        INSERT INTO consumer_refresh_tokens (consumer_id, token_hash, expires_at)
                        VALUES (:consumerId, :hash, :expiresAt)
                        """)
                .param("consumerId", consumerId)
                .param("hash", tokenHash)
                .param("expiresAt", expiresAt)
                .update();
    }

    public Optional<StoredConsumerToken> findValidConsumerToken(String tokenHash) {
        return db.sql("""
                        SELECT crt.id, crt.consumer_id, cu.email
                        FROM consumer_refresh_tokens crt
                        JOIN consumer_users cu ON crt.consumer_id = cu.id
                        WHERE crt.token_hash = :hash
                          AND crt.is_revoked = false
                          AND crt.expires_at > NOW()
                          AND cu.is_active = true
                        """)
                .param("hash", tokenHash)
                .query((rs, n) -> new StoredConsumerToken(
                        rs.getLong("id"),
                        rs.getLong("consumer_id"),
                        rs.getString("email")))
                .optional();
    }

    public void revokeConsumerToken(String tokenHash) {
        db.sql("UPDATE consumer_refresh_tokens SET is_revoked = true WHERE token_hash = :hash")
                .param("hash", tokenHash)
                .update();
    }

    /**
     * Clears out rows that can no longer authorise anything. The Node code defines an equivalent
     * helper but never calls it, so those tables grow without bound; this one runs on a schedule.
     */
    public int deleteExpired() {
        int users = db.sql("DELETE FROM refresh_tokens WHERE expires_at < NOW() OR is_revoked = true")
                .update();
        int consumers = db.sql(
                        "DELETE FROM consumer_refresh_tokens WHERE expires_at < NOW() OR is_revoked = true")
                .update();
        return users + consumers;
    }

    public record StoredUserToken(long id, long userId, String email, int roleId, String roleName) {
    }

    public record StoredConsumerToken(long id, long consumerId, String email) {
    }
}
