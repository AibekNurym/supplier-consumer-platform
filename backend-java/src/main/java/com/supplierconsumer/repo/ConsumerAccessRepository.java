package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The relationship between a buyer and a supplier, which is spread over three tables that all key
 * on the same {@code (consumer, company)} pair:
 *
 * <ul>
 *   <li>{@code company_consumer_requests} -- the buyer's application and its status;</li>
 *   <li>{@code consumer_company_access} -- the grant that actually decides what they can see;</li>
 *   <li>{@code company_consumer_blocks} -- the block list.</li>
 * </ul>
 *
 * <p>The request status is a plain varchar with no constraint, but the code drives it through
 * exactly six values: pending, approved, rejected, revoked, blocked and cancelled. The console
 * rewrites some of them for display, so all six have to be emitted as they are.
 */
@Repository
public class ConsumerAccessRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public ConsumerAccessRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public List<Map<String, Object>> findPendingRequests(long companyId) {
        return db.sql("""
                        SELECT ccr.id, ccr.consumer_id, ccr.status, ccr.requested_at,
                               ccr.responded_at,
                               cu.first_name, cu.last_name, cu.email, cu.phone
                        FROM company_consumer_requests ccr
                        JOIN consumer_users cu ON ccr.consumer_id = cu.id
                        WHERE ccr.company_id = :companyId AND ccr.status = 'pending'
                        ORDER BY ccr.requested_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public List<Map<String, Object>> findAllRequests(long companyId) {
        return db.sql("""
                        SELECT ccr.id, ccr.consumer_id, ccr.status, ccr.requested_at,
                               ccr.responded_at,
                               cu.first_name, cu.last_name, cu.email, cu.phone,
                               u.first_name AS reviewed_by_first_name,
                               u.last_name AS reviewed_by_last_name
                        FROM company_consumer_requests ccr
                        JOIN consumer_users cu ON ccr.consumer_id = cu.id
                        LEFT JOIN users u ON ccr.responded_by = u.id
                        WHERE ccr.company_id = :companyId
                        ORDER BY ccr.requested_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public Optional<RequestRow> findRequest(long requestId, long companyId) {
        return db.sql("""
                        SELECT ccr.id, ccr.consumer_id, ccr.status,
                               cu.first_name, cu.last_name, cu.email
                        FROM company_consumer_requests ccr
                        JOIN consumer_users cu ON ccr.consumer_id = cu.id
                        WHERE ccr.id = :id AND ccr.company_id = :companyId
                        """)
                .param("id", requestId)
                .param("companyId", companyId)
                .query((rs, n) -> new RequestRow(rs.getLong("id"), rs.getLong("consumer_id"),
                        rs.getString("status"), rs.getString("first_name"), rs.getString("last_name"),
                        rs.getString("email")))
                .optional();
    }

    public void setRequestStatus(long requestId, String status, Long respondedBy) {
        db.sql("""
                        UPDATE company_consumer_requests
                        SET status = :status, responded_at = NOW(), responded_by = :respondedBy
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("respondedBy", respondedBy)
                .param("id", requestId)
                .update();
    }

    public void setRequestStatusForConsumer(long companyId, long consumerId, String status,
                                            Long respondedBy) {
        db.sql("""
                        UPDATE company_consumer_requests
                        SET status = :status, responded_at = NOW(), responded_by = :respondedBy
                        WHERE company_id = :companyId AND consumer_id = :consumerId
                        """)
                .param("status", status)
                .param("respondedBy", respondedBy)
                .param("companyId", companyId)
                .param("consumerId", consumerId)
                .update();
    }

    /** Unblocking puts the buyer back in the queue: pending, with the previous response cleared. */
    public void resetRequestToPending(long companyId, long consumerId) {
        db.sql("""
                        UPDATE company_consumer_requests
                        SET status = 'pending', responded_at = NULL, responded_by = NULL
                        WHERE company_id = :companyId AND consumer_id = :consumerId
                        """)
                .param("companyId", companyId)
                .param("consumerId", consumerId)
                .update();
    }

    /**
     * Grants access, reviving a previously revoked grant rather than inserting a duplicate -- the
     * pair is unique, so a second approval has to update in place.
     */
    public void grantAccess(long consumerId, long companyId, Long grantedBy) {
        db.sql("""
                        INSERT INTO consumer_company_access
                            (consumer_id, company_id, granted_by, is_active, granted_at, revoked_at)
                        VALUES (:consumerId, :companyId, :grantedBy, TRUE, NOW(), NULL)
                        ON CONFLICT (consumer_id, company_id)
                        DO UPDATE SET is_active = TRUE,
                                      granted_at = NOW(),
                                      granted_by = EXCLUDED.granted_by,
                                      revoked_at = NULL
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .param("grantedBy", grantedBy)
                .update();
    }

    /**
     * Grants access only if nothing is recorded yet, leaving an existing row -- including a revoked
     * one -- untouched. Used when placing an order, which should enable conversation without
     * quietly reinstating access a supplier deliberately withdrew.
     */
    public void grantAccessIfAbsent(long consumerId, long companyId) {
        db.sql("""
                        INSERT INTO consumer_company_access (consumer_id, company_id, granted_by, granted_at)
                        VALUES (:consumerId, :companyId, NULL, NOW())
                        ON CONFLICT (consumer_id, company_id) DO NOTHING
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .update();
    }

    public void revokeAccessById(long accessId) {
        db.sql("""
                        UPDATE consumer_company_access
                        SET is_active = FALSE, revoked_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", accessId)
                .update();
    }

    public void revokeAccessForConsumer(long companyId, long consumerId) {
        db.sql("""
                        UPDATE consumer_company_access
                        SET is_active = FALSE, revoked_at = NOW()
                        WHERE company_id = :companyId AND consumer_id = :consumerId
                          AND is_active = TRUE
                        """)
                .param("companyId", companyId)
                .param("consumerId", consumerId)
                .update();
    }

    public Optional<AccessRow> findAccess(long accessId, long companyId) {
        return db.sql("""
                        SELECT cca.id, cca.consumer_id, cu.first_name, cu.last_name, cu.email
                        FROM consumer_company_access cca
                        JOIN consumer_users cu ON cca.consumer_id = cu.id
                        WHERE cca.id = :id AND cca.company_id = :companyId
                        """)
                .param("id", accessId)
                .param("companyId", companyId)
                .query((rs, n) -> new AccessRow(rs.getLong("id"), rs.getLong("consumer_id"),
                        rs.getString("first_name"), rs.getString("last_name"), rs.getString("email")))
                .optional();
    }

    /** Whether the buyer currently has a live grant -- the check every catalogue read makes. */
    public boolean hasActiveAccess(long consumerId, long companyId) {
        return db.sql("""
                        SELECT 1 FROM consumer_company_access
                        WHERE consumer_id = :consumerId AND company_id = :companyId
                          AND is_active = TRUE
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public List<Map<String, Object>> findCompanyConsumers(long companyId) {
        return db.sql("""
                        SELECT cca.id, cca.consumer_id, cca.granted_at AS access_granted_at,
                               cca.is_active,
                               COALESCE(blocks.is_blocked, false) AS is_blocked,
                               blocks.blocked_at,
                               cu.first_name, cu.last_name, cu.email, cu.phone,
                               u.first_name AS granted_by_first_name,
                               u.last_name AS granted_by_last_name
                        FROM consumer_company_access cca
                        JOIN consumer_users cu ON cca.consumer_id = cu.id
                        LEFT JOIN users u ON cca.granted_by = u.id
                        LEFT JOIN company_consumer_blocks blocks
                          ON blocks.company_id = cca.company_id
                         AND blocks.consumer_id = cca.consumer_id
                         AND blocks.is_blocked = TRUE
                        WHERE cca.company_id = :companyId AND cca.is_active = TRUE
                        ORDER BY cca.granted_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public List<Map<String, Object>> findBlockedConsumers(long companyId) {
        return db.sql("""
                        SELECT b.id, b.consumer_id, b.blocked_at,
                               cu.first_name, cu.last_name, cu.email, cu.phone,
                               ub.first_name AS blocked_by_first_name,
                               ub.last_name AS blocked_by_last_name
                        FROM company_consumer_blocks b
                        JOIN consumer_users cu ON b.consumer_id = cu.id
                        LEFT JOIN users ub ON b.blocked_by = ub.id
                        WHERE b.company_id = :companyId AND b.is_blocked = TRUE
                        ORDER BY b.blocked_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public void block(long companyId, long consumerId, long blockedBy) {
        db.sql("""
                        INSERT INTO company_consumer_blocks
                            (company_id, consumer_id, blocked_by, is_blocked, blocked_at,
                             unblocked_by, unblocked_at)
                        VALUES (:companyId, :consumerId, :blockedBy, TRUE, CURRENT_TIMESTAMP,
                                NULL, NULL)
                        ON CONFLICT (company_id, consumer_id)
                        DO UPDATE SET is_blocked = TRUE,
                                      blocked_by = EXCLUDED.blocked_by,
                                      blocked_at = CURRENT_TIMESTAMP,
                                      unblocked_by = NULL,
                                      unblocked_at = NULL
                        """)
                .param("companyId", companyId)
                .param("consumerId", consumerId)
                .param("blockedBy", blockedBy)
                .update();
    }

    /** Returns how many rows changed, so the caller can tell "not blocked" from "unblocked". */
    public int unblock(long companyId, long consumerId, long unblockedBy) {
        return db.sql("""
                        UPDATE company_consumer_blocks
                        SET is_blocked = FALSE, unblocked_at = CURRENT_TIMESTAMP,
                            unblocked_by = :unblockedBy
                        WHERE company_id = :companyId AND consumer_id = :consumerId
                          AND is_blocked = TRUE
                        """)
                .param("unblockedBy", unblockedBy)
                .param("companyId", companyId)
                .param("consumerId", consumerId)
                .update();
    }

    public boolean isBlocked(long consumerId, long companyId) {
        return db.sql("""
                        SELECT 1 FROM company_consumer_blocks
                        WHERE company_id = :companyId AND consumer_id = :consumerId
                          AND is_blocked = TRUE
                        """)
                .param("companyId", companyId)
                .param("consumerId", consumerId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public Optional<ConsumerRow> findConsumer(long consumerId) {
        return db.sql("SELECT id, first_name, last_name, email FROM consumer_users WHERE id = :id")
                .param("id", consumerId)
                .query((rs, n) -> new ConsumerRow(rs.getLong("id"), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getString("email")))
                .optional();
    }

    public record RequestRow(long id, long consumerId, String status, String firstName,
                             String lastName, String email) {
        public String fullName() {
            return firstName + " " + lastName;
        }
    }

    public record AccessRow(long id, long consumerId, String firstName, String lastName, String email) {
        public String fullName() {
            return firstName + " " + lastName;
        }
    }

    public record ConsumerRow(long id, String firstName, String lastName, String email) {
        public String fullName() {
            return firstName + " " + lastName;
        }
    }
}
