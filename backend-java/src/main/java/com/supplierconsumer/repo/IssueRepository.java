package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issues raised by buyers against an order.
 *
 * <p>The lifecycle is {@code reported → assigned_to_manager → resolved}. The check constraint also
 * permits {@code assigned_to_sales} and {@code closed}, but nothing writes them -- they are
 * leftovers from a design that was simplified, and every employee can now see and resolve any of
 * their company's issues regardless of assignment.
 */
@Repository
public class IssueRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public IssueRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public Optional<OrderForIssue> findOrderForConsumer(long orderId, long consumerId) {
        return db.sql("""
                        SELECT id, company_id FROM consumer_orders
                        WHERE id = :orderId AND consumer_id = :consumerId
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .param("consumerId", consumerId)
                .query((rs, n) -> new OrderForIssue(rs.getLong("id"), rs.getLong("company_id")))
                .optional();
    }

    public Map<String, Object> insert(long orderId, long consumerId, long companyId, String title,
                                      String description) {
        return db.sql("""
                        INSERT INTO order_issues
                            (order_id, consumer_id, company_id, reported_by, assigned_to,
                             assigned_to_role, status, title, description)
                        VALUES (:orderId, :consumerId, :companyId, :consumerId, NULL, NULL,
                                'reported', :title, :description)
                        RETURNING id, order_id, consumer_id, company_id, reported_by, assigned_to,
                                  assigned_to_role, status, title, description, resolution_notes,
                                  resolved_by, reported_at, assigned_at, resolved_at, created_at,
                                  updated_at
                        """)
                .param("orderId", orderId)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .param("title", title)
                .param("description", description)
                .query(pgJson.rowMapper())
                .single();
    }

    /** Context for the summary posted into the conversation when an issue is raised. */
    public Optional<OrderContext> findOrderContext(long orderId) {
        return db.sql("""
                        SELECT co.total_amount, co.payment_method, co.delivery_method,
                               co.delivery_address, c.name AS company_name
                        FROM consumer_orders co
                        JOIN companies c ON co.company_id = c.id
                        WHERE co.id = :orderId
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .query((rs, n) -> new OrderContext(rs.getBigDecimal("total_amount"),
                        rs.getString("payment_method"), rs.getString("delivery_method"),
                        rs.getString("delivery_address"), rs.getString("company_name")))
                .optional();
    }

    public List<ItemLine> findItemLines(long orderId) {
        return db.sql("""
                        SELECT product_name, quantity, total_price
                        FROM consumer_order_items
                        WHERE order_id = :orderId
                        ORDER BY id ASC
                        """)
                .param("orderId", orderId)
                .query((rs, n) -> new ItemLine(rs.getString("product_name"), rs.getInt("quantity"),
                        rs.getBigDecimal("total_price")))
                .list();
    }

    public List<Map<String, Object>> findForConsumer(long consumerId) {
        return db.sql("""
                        SELECT oi.id, oi.order_id, oi.consumer_id, oi.company_id, oi.reported_by,
                               oi.assigned_to, oi.assigned_to_role, oi.status, oi.title,
                               oi.description, oi.resolution_notes, oi.resolved_by, oi.reported_at,
                               oi.assigned_at, oi.resolved_at, oi.created_at, oi.updated_at,
                               co.total_amount, co.status AS order_status,
                               co.created_at AS order_created_at,
                               c.name AS company_name,
                               u.first_name || ' ' || u.last_name AS assigned_to_name,
                               ru.first_name || ' ' || ru.last_name AS resolved_by_name
                        FROM order_issues oi
                        JOIN consumer_orders co ON oi.order_id = co.id
                        JOIN companies c ON oi.company_id = c.id
                        LEFT JOIN users u ON oi.assigned_to = u.id
                        LEFT JOIN users ru ON oi.resolved_by = ru.id
                        WHERE oi.consumer_id = :consumerId
                        ORDER BY oi.reported_at DESC
                        """)
                .param("consumerId", consumerId)
                .query(pgJson.rowMapper())
                .list();
    }

    /**
     * The supplier's issue list, ordered by urgency rather than by status name: unattended first,
     * then assigned, then resolved, and newest first within each group.
     */
    public List<Map<String, Object>> findForCompany(long companyId) {
        return db.sql("""
                        SELECT oi.id, oi.order_id, oi.consumer_id, oi.company_id, oi.reported_by,
                               oi.assigned_to, oi.assigned_to_role, oi.status, oi.title,
                               oi.description, oi.resolution_notes, oi.resolved_by, oi.reported_at,
                               oi.assigned_at, oi.resolved_at, oi.created_at, oi.updated_at,
                               co.total_amount, co.status AS order_status,
                               co.created_at AS order_created_at,
                               cu.first_name || ' ' || cu.last_name AS consumer_name,
                               cu.email AS consumer_email, cu.phone AS consumer_phone,
                               u.first_name || ' ' || u.last_name AS assigned_to_name,
                               ru.first_name || ' ' || ru.last_name AS resolved_by_name
                        FROM order_issues oi
                        JOIN consumer_orders co ON oi.order_id = co.id
                        JOIN consumer_users cu ON oi.consumer_id = cu.id
                        LEFT JOIN users u ON oi.assigned_to = u.id
                        LEFT JOIN users ru ON oi.resolved_by = ru.id
                        WHERE oi.company_id = :companyId
                        ORDER BY CASE oi.status
                                     WHEN 'reported' THEN 1
                                     WHEN 'assigned_to_manager' THEN 2
                                     WHEN 'resolved' THEN 3
                                     WHEN 'closed' THEN 4
                                 END,
                                 oi.reported_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public Optional<IssueRow> findIssue(long issueId) {
        return db.sql("""
                        SELECT oi.id, oi.order_id, oi.consumer_id, oi.company_id, oi.status,
                               oi.title, oi.description
                        FROM order_issues oi
                        WHERE oi.id = :id
                        """)
                .param("id", issueId)
                .query((rs, n) -> new IssueRow(rs.getLong("id"), rs.getLong("order_id"),
                        rs.getLong("consumer_id"), rs.getLong("company_id"), rs.getString("status"),
                        rs.getString("title"), rs.getString("description")))
                .optional();
    }

    /** Assignment targets: Managers and Owners of the company, both eligible. */
    public List<Map<String, Object>> findAssignableStaff(long companyId) {
        return db.sql("""
                        SELECT u.id, u.first_name, u.last_name, u.email, r.name AS role_name
                        FROM users u
                        JOIN roles r ON u.role_id = r.id
                        WHERE u.company_id = :companyId AND u.is_active = true
                          AND r.name IN ('Manager', 'Owner')
                        ORDER BY r.name, u.first_name, u.last_name
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public Optional<String> findAssignableRole(long userId, long companyId) {
        return db.sql("""
                        SELECT r.name FROM users u
                        JOIN roles r ON u.role_id = r.id
                        WHERE u.id = :userId AND u.company_id = :companyId AND u.is_active = true
                          AND r.name IN ('Manager', 'Owner')
                        """)
                .param("userId", userId)
                .param("companyId", companyId)
                .query(String.class)
                .optional();
    }

    public Map<String, Object> assign(long issueId, long assigneeId, String assigneeRole) {
        return db.sql("""
                        UPDATE order_issues
                        SET assigned_to = :assigneeId, assigned_to_role = :assigneeRole,
                            status = 'assigned_to_manager', assigned_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        RETURNING id, order_id, consumer_id, company_id, reported_by, assigned_to,
                                  assigned_to_role, status, title, description, resolution_notes,
                                  resolved_by, reported_at, assigned_at, resolved_at, created_at,
                                  updated_at
                        """)
                .param("assigneeId", assigneeId)
                .param("assigneeRole", assigneeRole)
                .param("id", issueId)
                .query(pgJson.rowMapper())
                .single();
    }

    public Map<String, Object> resolve(long issueId, long resolvedBy, String resolutionNotes) {
        return db.sql("""
                        UPDATE order_issues
                        SET status = 'resolved', resolved_by = :resolvedBy,
                            resolution_notes = :notes, resolved_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        RETURNING id, order_id, consumer_id, company_id, reported_by, assigned_to,
                                  assigned_to_role, status, title, description, resolution_notes,
                                  resolved_by, reported_at, assigned_at, resolved_at, created_at,
                                  updated_at
                        """)
                .param("resolvedBy", resolvedBy)
                .param("notes", resolutionNotes)
                .param("id", issueId)
                .query(pgJson.rowMapper())
                .single();
    }

    /**
     * One issue with its order and line items, for the buyer.
     *
     * <p>The line items come back through ARRAY_AGG(JSONB_BUILD_OBJECT(...)), which matters for the
     * wire format: numerics inside jsonb are parsed as JavaScript numbers, so unit_price appears
     * here as 1200 while the very same column selected directly elsewhere is the string "1200.00".
     *
     * <p>The inner join to the line items is not a LEFT JOIN, so an issue on an order with no items
     * yields nothing and the endpoint answers 404. Preserved as-is.
     *
     * <p>The original reads the wrong request attribute on this path and so fails on every call;
     * the consumer is identified correctly here.
     */
    public Optional<Map<String, Object>> findDetailForConsumer(long issueId, long consumerId) {
        return db.sql("""
                        SELECT oi.id, oi.order_id, oi.consumer_id, oi.company_id, oi.reported_by,
                               oi.assigned_to, oi.assigned_to_role, oi.status, oi.title,
                               oi.description, oi.resolution_notes, oi.resolved_by, oi.reported_at,
                               oi.assigned_at, oi.resolved_at, oi.created_at, oi.updated_at,
                               co.total_amount, co.payment_method, co.delivery_method,
                               co.delivery_address, co.status AS order_status,
                               co.created_at AS order_created_at,
                               c.name AS company_name,
                               u.first_name || ' ' || u.last_name AS assigned_to_name,
                               ru.first_name || ' ' || ru.last_name AS resolved_by_name,
                               ARRAY_AGG(JSONB_BUILD_OBJECT(
                                   'product_id', coi.product_id,
                                   'product_name', coi.product_name,
                                   'quantity', coi.quantity,
                                   'unit_price', coi.unit_price,
                                   'total_price', coi.total_price)) AS order_items
                        FROM order_issues oi
                        JOIN consumer_orders co ON oi.order_id = co.id
                        JOIN consumer_order_items coi ON co.id = coi.order_id
                        JOIN companies c ON oi.company_id = c.id
                        LEFT JOIN users u ON oi.assigned_to = u.id
                        LEFT JOIN users ru ON oi.resolved_by = ru.id
                        WHERE oi.id = :issueId AND oi.consumer_id = :consumerId
                        GROUP BY oi.id, co.id, c.id, u.id, ru.id
                        """)
                .param("issueId", issueId)
                .param("consumerId", consumerId)
                .query(pgJson.rowMapper())
                .optional();
    }

    /** The same view for the supplier, carrying the buyer's contact details instead. */
    public Optional<Map<String, Object>> findDetailForCompany(long issueId, long companyId) {
        return db.sql("""
                        SELECT oi.id, oi.order_id, oi.consumer_id, oi.company_id, oi.reported_by,
                               oi.assigned_to, oi.assigned_to_role, oi.status, oi.title,
                               oi.description, oi.resolution_notes, oi.resolved_by, oi.reported_at,
                               oi.assigned_at, oi.resolved_at, oi.created_at, oi.updated_at,
                               co.total_amount, co.payment_method, co.delivery_method,
                               co.delivery_address, co.status AS order_status,
                               co.created_at AS order_created_at,
                               cu.first_name || ' ' || cu.last_name AS consumer_name,
                               cu.email AS consumer_email, cu.phone AS consumer_phone,
                               u.first_name || ' ' || u.last_name AS assigned_to_name,
                               ru.first_name || ' ' || ru.last_name AS resolved_by_name,
                               ARRAY_AGG(JSONB_BUILD_OBJECT(
                                   'product_id', coi.product_id,
                                   'product_name', coi.product_name,
                                   'quantity', coi.quantity,
                                   'unit_price', coi.unit_price,
                                   'total_price', coi.total_price)) AS order_items
                        FROM order_issues oi
                        JOIN consumer_orders co ON oi.order_id = co.id
                        JOIN consumer_order_items coi ON co.id = coi.order_id
                        JOIN consumer_users cu ON oi.consumer_id = cu.id
                        JOIN companies c ON oi.company_id = c.id
                        LEFT JOIN users u ON oi.assigned_to = u.id
                        LEFT JOIN users ru ON oi.resolved_by = ru.id
                        WHERE oi.id = :issueId AND oi.company_id = :companyId
                        GROUP BY oi.id, co.id, cu.id, c.id, u.id, ru.id
                        """)
                .param("issueId", issueId)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .optional();
    }

    public record OrderForIssue(long id, long companyId) {
    }

    public record OrderContext(java.math.BigDecimal totalAmount, String paymentMethod,
                               String deliveryMethod, String deliveryAddress, String companyName) {
    }

    public record ItemLine(String productName, int quantity, java.math.BigDecimal totalPrice) {
    }

    public record IssueRow(long id, long orderId, long consumerId, long companyId, String status,
                           String title, String description) {
    }
}
