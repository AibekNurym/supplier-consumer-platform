package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The buyer-facing view of suppliers and their catalogues. */
@Repository
public class CatalogRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public CatalogRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    /**
     * The public supplier list, which anonymous callers may read too.
     *
     * <p>The block lookup is guarded on the consumer id being present, so an anonymous request
     * skips the join entirely and always reports {@code is_blocked} false. {@code product_count}
     * is a bare {@code COUNT(*)}, so it goes out as a string.
     */
    public List<Map<String, Object>> findCompanies(Long consumerId) {
        return db.sql("""
                        SELECT c.id, c.name, c.description, c.created_at,
                               COUNT(p.id) AS product_count,
                               CASE WHEN CAST(:consumerId AS INT) IS NOT NULL
                                    THEN COALESCE(blocks.is_blocked, false)
                                    ELSE false END AS is_blocked
                        FROM companies c
                        LEFT JOIN products p ON c.id = p.company_id
                        LEFT JOIN company_consumer_blocks blocks
                          ON CAST(:consumerId AS INT) IS NOT NULL
                         AND blocks.company_id = c.id
                         AND blocks.consumer_id = CAST(:consumerId AS INT)
                         AND blocks.is_blocked = TRUE
                        WHERE c.is_active = true
                        GROUP BY c.id, c.name, c.description, c.created_at, blocks.is_blocked
                        ORDER BY c.created_at DESC
                        """)
                .param("consumerId", consumerId)
                .query(pgJson.rowMapper())
                .list();
    }

    /** Every supplier annotated with this buyer's standing: requested, granted, or blocked. */
    public List<Map<String, Object>> findCompaniesWithStatus(long consumerId) {
        return db.sql("""
                        SELECT c.id, c.name, c.description, c.created_at,
                               COUNT(p.id) AS product_count,
                               ccr.status AS request_status,
                               ccr.requested_at, ccr.responded_at,
                               cca.is_active AS has_access,
                               COALESCE(blocks.is_blocked, false) AS is_blocked
                        FROM companies c
                        LEFT JOIN products p ON c.id = p.company_id
                        LEFT JOIN company_consumer_requests ccr
                          ON c.id = ccr.company_id AND ccr.consumer_id = :consumerId
                        LEFT JOIN consumer_company_access cca
                          ON c.id = cca.company_id AND cca.consumer_id = :consumerId
                        LEFT JOIN company_consumer_blocks blocks
                          ON blocks.company_id = c.id AND blocks.consumer_id = :consumerId
                         AND blocks.is_blocked = TRUE
                        WHERE c.is_active = true
                        GROUP BY c.id, c.name, c.description, c.created_at, ccr.status,
                                 ccr.requested_at, ccr.responded_at, cca.is_active,
                                 blocks.is_blocked
                        ORDER BY c.created_at DESC
                        """)
                .param("consumerId", consumerId)
                .query(pgJson.rowMapper())
                .list();
    }

    /**
     * The catalogue as a buyer with a live grant sees it: real prices, plus the discounted price
     * computed in SQL.
     */
    public List<Map<String, Object>> findProductsWithPrices(long companyId) {
        return db.sql("""
                        SELECT id, name, image, price, discount_percentage, lead_time_days,
                               CASE WHEN discount_percentage > 0
                                    THEN ROUND(price * (1 - discount_percentage / 100), 2)
                                    ELSE price END AS discounted_price,
                               minimum_order_quantity, available_quantity, created_at
                        FROM products
                        WHERE company_id = :companyId
                        ORDER BY created_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    /**
     * The same catalogue without a grant. The key set is identical but the three price fields are
     * null -- the buyer sees what is on offer but not what it costs.
     *
     * <p>The column order differs from the priced query: there {@code discounted_price} follows
     * {@code lead_time_days}, here it precedes it. That is how the two statements are written in
     * the original, and it is visible in the JSON, so it is kept.
     */
    public List<Map<String, Object>> findProductsWithoutPrices(long companyId) {
        return db.sql("""
                        SELECT id, name, image,
                               NULL AS price, NULL AS discount_percentage, NULL AS discounted_price,
                               lead_time_days, minimum_order_quantity, available_quantity, created_at
                        FROM products
                        WHERE company_id = :companyId
                        ORDER BY created_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public Optional<String> findActiveCompanyName(long companyId) {
        return db.sql("SELECT name FROM companies WHERE id = :id AND is_active = true")
                .param("id", companyId)
                .query(String.class)
                .optional();
    }

    public Optional<ExistingRequest> findRequest(long consumerId, long companyId) {
        return db.sql("""
                        SELECT id, status, requested_at
                        FROM company_consumer_requests
                        WHERE consumer_id = :consumerId AND company_id = :companyId
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .query((rs, n) -> new ExistingRequest(rs.getLong("id"), rs.getString("status"),
                        pgJson.jsDate(rs.getObject("requested_at", java.time.LocalDateTime.class))))
                .optional();
    }

    /** Reopens a closed request instead of creating a second one -- the pair is unique. */
    public ExistingRequest reopenRequest(long requestId) {
        return db.sql("""
                        UPDATE company_consumer_requests
                        SET status = 'pending', requested_at = NOW(), responded_at = NULL,
                            responded_by = NULL
                        WHERE id = :id
                        RETURNING id, status, requested_at
                        """)
                .param("id", requestId)
                .query((rs, n) -> new ExistingRequest(rs.getLong("id"), rs.getString("status"),
                        pgJson.jsDate(rs.getObject("requested_at", java.time.LocalDateTime.class))))
                .single();
    }

    public ExistingRequest createRequest(long consumerId, long companyId) {
        return db.sql("""
                        INSERT INTO company_consumer_requests (consumer_id, company_id, status)
                        VALUES (:consumerId, :companyId, 'pending')
                        RETURNING id, status, requested_at
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .query((rs, n) -> new ExistingRequest(rs.getLong("id"), rs.getString("status"),
                        pgJson.jsDate(rs.getObject("requested_at", java.time.LocalDateTime.class))))
                .single();
    }

    /**
     * This buyer's requests.
     *
     * <p>The original selects {@code ccr.created_at} and {@code ccr.updated_at}, neither of which
     * exists on this table -- the columns are {@code requested_at} and {@code responded_at} -- so
     * the endpoint fails every time it is called. Fixed here to select the real columns, keeping
     * the aliases the response already used.
     */
    public List<Map<String, Object>> findRequestsForConsumer(long consumerId) {
        return db.sql("""
                        SELECT ccr.id, ccr.status,
                               ccr.requested_at AS created_at,
                               ccr.responded_at AS updated_at,
                               c.name AS company_name,
                               c.description AS company_description
                        FROM company_consumer_requests ccr
                        JOIN companies c ON ccr.company_id = c.id
                        WHERE ccr.consumer_id = :consumerId
                        ORDER BY ccr.requested_at DESC
                        """)
                .param("consumerId", consumerId)
                .query(pgJson.rowMapper())
                .list();
    }

    public List<Map<String, Object>> findAccessibleCompanies(long consumerId) {
        return db.sql("""
                        SELECT c.id, c.name, c.description,
                               cca.granted_at AS access_granted_at,
                               COALESCE(blocks.is_blocked, false) AS is_blocked
                        FROM consumer_company_access cca
                        JOIN companies c ON cca.company_id = c.id
                        LEFT JOIN company_consumer_blocks blocks
                          ON blocks.company_id = c.id AND blocks.consumer_id = :consumerId
                         AND blocks.is_blocked = TRUE
                        WHERE cca.consumer_id = :consumerId AND cca.is_active = true
                          AND c.is_active = true
                        ORDER BY cca.granted_at DESC
                        """)
                .param("consumerId", consumerId)
                .query(pgJson.rowMapper())
                .list();
    }

    public record ExistingRequest(long id, String status, String requestedAt) {
    }
}
