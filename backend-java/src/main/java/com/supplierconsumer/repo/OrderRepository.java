package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Orders and their line items. */
@Repository
public class OrderRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public OrderRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public long insertOrder(long consumerId, long companyId, String paymentMethod,
                            String deliveryMethod, String deliveryAddress, String deliveryCoordinates,
                            BigDecimal totalAmount) {
        KeyHolder keys = new GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO consumer_orders
                            (consumer_id, company_id, payment_method, delivery_method,
                             delivery_address, delivery_coordinates, total_amount, status)
                        VALUES (:consumerId, :companyId, :paymentMethod, :deliveryMethod,
                                :deliveryAddress, CAST(:coordinates AS point), :totalAmount, 'pending')
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .param("paymentMethod", paymentMethod)
                .param("deliveryMethod", deliveryMethod)
                .param("deliveryAddress", deliveryAddress)
                .param("coordinates", deliveryCoordinates)
                .param("totalAmount", totalAmount)
                .update(keys, "id");
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    public void insertOrderItem(long orderId, Long productId, String productName, int quantity,
                                BigDecimal unitPrice, BigDecimal totalPrice) {
        db.sql("""
                        INSERT INTO consumer_order_items
                            (order_id, product_id, product_name, quantity, unit_price, total_price)
                        VALUES (:orderId, :productId, :productName, :quantity, :unitPrice, :totalPrice)
                        """)
                .param("orderId", orderId)
                .param("productId", productId)
                .param("productName", productName)
                .param("quantity", quantity)
                .param("unitPrice", unitPrice)
                .param("totalPrice", totalPrice)
                .update();
    }

    /**
     * Locks the order row.
     *
     * <p>This is the linchpin of the accept/reject/complete paths. Holding it serialises the three
     * transitions against one another, and it is what makes the idempotent "already accepted"
     * response correct: the second of two simultaneous accepts blocks here, then re-reads the
     * status under the lock and sees the first one's result.
     */
    public Optional<OrderRow> lockOrder(long orderId) {
        return db.sql("""
                        SELECT id, consumer_id, company_id, status
                        FROM consumer_orders
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .param("id", orderId)
                .query((rs, n) -> new OrderRow(rs.getLong("id"), rs.getLong("consumer_id"),
                        rs.getLong("company_id"), rs.getString("status")))
                .optional();
    }

    public void setStatus(long orderId, String status) {
        db.sql("UPDATE consumer_orders SET status = :status, updated_at = NOW() WHERE id = :id")
                .param("status", status)
                .param("id", orderId)
                .update();
    }

    /**
     * Orders awaiting action. Despite the endpoint's name this includes accepted orders -- it is
     * the supplier's active-work list, not a strictly pending one.
     */
    public List<Map<String, Object>> findActiveForCompany(long companyId) {
        return db.sql("""
                        SELECT o.id, o.created_at, o.payment_method, o.delivery_method,
                               o.delivery_address, o.total_amount, o.status, o.consumer_id,
                               cu.first_name, cu.last_name, cu.email, cu.phone
                        FROM consumer_orders o
                        JOIN consumer_users cu ON o.consumer_id = cu.id
                        WHERE o.company_id = :companyId AND o.status IN ('pending', 'accepted')
                        ORDER BY o.created_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public List<Map<String, Object>> findCompletedForCompany(long companyId) {
        return db.sql("""
                        SELECT o.id, o.created_at, o.payment_method, o.delivery_method,
                               o.delivery_address, o.total_amount, o.status, o.consumer_id,
                               cu.first_name, cu.last_name, cu.email, cu.phone
                        FROM consumer_orders o
                        JOIN consumer_users cu ON o.consumer_id = cu.id
                        WHERE o.company_id = :companyId AND o.status = 'completed'
                        ORDER BY o.created_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    /** The buyer's own orders, flagged with whether any issue on them has been resolved. */
    public List<Map<String, Object>> findForConsumer(long consumerId) {
        return db.sql("""
                        SELECT o.id, o.created_at, o.payment_method, o.delivery_method,
                               o.delivery_address, o.total_amount, o.status, o.company_id,
                               c.name AS company_name,
                               EXISTS (SELECT 1 FROM order_issues oi
                                       WHERE oi.order_id = o.id AND oi.status = 'resolved')
                                   AS has_resolved_issue
                        FROM consumer_orders o
                        JOIN companies c ON o.company_id = c.id
                        WHERE o.consumer_id = :consumerId
                        ORDER BY o.created_at DESC
                        """)
                .param("consumerId", consumerId)
                .query(pgJson.rowMapper())
                .list();
    }

    /**
     * Line items for a set of orders, fetched in one statement.
     *
     * <p>The original loops and issues one query per order, which is an N+1 on every list endpoint.
     * Same rows, same shape, one round trip.
     */
    public List<Map<String, Object>> findItemsForOrders(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return db.sql("""
                        SELECT order_id, product_id, product_name, quantity, unit_price, total_price
                        FROM consumer_order_items
                        WHERE order_id IN (:orderIds)
                        ORDER BY id
                        """)
                .param("orderIds", orderIds)
                .query(pgJson.rowMapper())
                .list();
    }

    public Optional<Map<String, Object>> findCustomer(long orderId, long companyId) {
        return db.sql("""
                        SELECT o.id, o.consumer_id, cu.first_name, cu.last_name, cu.email, cu.phone
                        FROM consumer_orders o
                        JOIN consumer_users cu ON o.consumer_id = cu.id
                        WHERE o.id = :orderId AND o.company_id = :companyId
                        """)
                .param("orderId", orderId)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .optional();
    }

    /** The line items joined to live stock, which is what the acceptance check works from. */
    public List<StockLine> findLinesWithStock(long orderId, long companyId) {
        return db.sql("""
                        SELECT oi.product_id, oi.product_name, oi.quantity,
                               p.available_quantity
                        FROM consumer_order_items oi
                        JOIN products p ON oi.product_id = p.id
                        WHERE oi.order_id = :orderId AND p.company_id = :companyId
                        ORDER BY oi.product_id
                        """)
                .param("orderId", orderId)
                .param("companyId", companyId)
                .query((rs, n) -> new StockLine(rs.getLong("product_id"), rs.getString("product_name"),
                        rs.getInt("quantity"), rs.getInt("available_quantity")))
                .list();
    }

    public boolean hasAnyItems(long orderId) {
        return db.sql("SELECT 1 FROM consumer_order_items WHERE order_id = :orderId LIMIT 1")
                .param("orderId", orderId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * Locks the given products, in ascending id order.
     *
     * <p>The ordering is what keeps two concurrent acceptances that share products from
     * deadlocking: both take the locks in the same sequence, so one simply waits.
     */
    public Map<Long, Integer> lockStock(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return db.sql("""
                        SELECT id, COALESCE(available_quantity, 0) AS available_quantity
                        FROM products
                        WHERE id IN (:ids)
                        ORDER BY id
                        FOR UPDATE
                        """)
                .param("ids", productIds.stream().sorted().toList())
                .query((rs, n) -> Map.entry(rs.getLong("id"), rs.getInt("available_quantity")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * A plain subtraction. The original clamps with {@code GREATEST(0, ...)}, which hides an
     * underflow rather than preventing it; under the locks above the value cannot go negative, and
     * a check constraint catches it loudly if it somehow does.
     */
    public void decrementStock(long productId, long companyId, int quantity) {
        db.sql("""
                        UPDATE products
                        SET available_quantity = available_quantity - :quantity, updated_at = NOW()
                        WHERE id = :id AND company_id = :companyId
                        """)
                .param("quantity", quantity)
                .param("id", productId)
                .param("companyId", companyId)
                .update();
    }

    public void restoreStock(long productId, long companyId, int quantity) {
        db.sql("""
                        UPDATE products
                        SET available_quantity = available_quantity + :quantity, updated_at = NOW()
                        WHERE id = :id AND company_id = :companyId
                        """)
                .param("quantity", quantity)
                .param("id", productId)
                .param("companyId", companyId)
                .update();
    }

    /** Completing an order closes out anything still open against it. */
    public int resolveOpenIssues(long orderId) {
        return db.sql("""
                        UPDATE order_issues
                        SET status = 'resolved',
                            resolution_notes = 'Order completed by consumer. All issues automatically resolved.',
                            resolved_at = NOW(), resolved_by = NULL, updated_at = NOW()
                        WHERE order_id = :orderId AND status != 'resolved'
                        """)
                .param("orderId", orderId)
                .update();
    }

    public Optional<Map<String, Object>> findEventRow(long orderId) {
        return db.sql("""
                        SELECT id, consumer_id, company_id, status, updated_at
                        FROM consumer_orders WHERE id = :id
                        """)
                .param("id", orderId)
                .query(pgJson.rowMapper())
                .optional();
    }

    public record OrderRow(long id, long consumerId, long companyId, String status) {
    }

    public record StockLine(long productId, String productName, int quantity, int availableQuantity) {
    }
}
