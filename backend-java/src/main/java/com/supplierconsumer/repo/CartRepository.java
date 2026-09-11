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

/** Carts. There is exactly one per buyer per supplier, enforced by a unique constraint. */
@Repository
public class CartRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public CartRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public Optional<Long> findCartId(long consumerId, long companyId) {
        return db.sql("""
                        SELECT id FROM consumer_carts
                        WHERE consumer_id = :consumerId AND company_id = :companyId
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .query(Long.class)
                .optional();
    }

    /**
     * Locks the cart row for the duration of the transaction.
     *
     * <p>Used by checkout so that two simultaneous submissions cannot both turn the same cart into
     * an order. Nothing in the original prevents that.
     */
    public Optional<Long> lockCart(long consumerId, long companyId) {
        return db.sql("""
                        SELECT id FROM consumer_carts
                        WHERE consumer_id = :consumerId AND company_id = :companyId
                        FOR UPDATE
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .query(Long.class)
                .optional();
    }

    public long createCart(long consumerId, long companyId) {
        KeyHolder keys = new GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO consumer_carts (consumer_id, company_id)
                        VALUES (:consumerId, :companyId)
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .update(keys, "id");
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    public List<Map<String, Object>> findItems(long cartId) {
        return db.sql("""
                        SELECT ci.id, ci.product_id, ci.quantity, ci.unit_price,
                               p.name, p.image, p.minimum_order_quantity, p.available_quantity,
                               p.lead_time_days
                        FROM consumer_cart_items ci
                        JOIN products p ON ci.product_id = p.id
                        WHERE ci.cart_id = :cartId
                        ORDER BY ci.created_at DESC
                        """)
                .param("cartId", cartId)
                .query(pgJson.rowMapper())
                .list();
    }

    /** The projection checkout needs: enough to build order lines from. */
    public List<CheckoutItem> findItemsForCheckout(long cartId) {
        return db.sql("""
                        SELECT ci.product_id, ci.quantity, ci.unit_price, p.name AS product_name
                        FROM consumer_cart_items ci
                        JOIN products p ON ci.product_id = p.id
                        WHERE ci.cart_id = :cartId
                        ORDER BY ci.id
                        """)
                .param("cartId", cartId)
                .query((rs, n) -> new CheckoutItem(rs.getLong("product_id"), rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price"), rs.getString("product_name")))
                .list();
    }

    /** The product as the cart sees it, including the discounted price computed in SQL. */
    public Optional<CartProduct> findProduct(long productId, long companyId) {
        return db.sql("""
                        SELECT id, name, price, discount_percentage,
                               CASE WHEN discount_percentage > 0
                                    THEN ROUND(price * (1 - discount_percentage / 100), 2)
                                    ELSE price END AS discounted_price,
                               minimum_order_quantity, available_quantity
                        FROM products
                        WHERE id = :productId AND company_id = :companyId
                        """)
                .param("productId", productId)
                .param("companyId", companyId)
                .query((rs, n) -> new CartProduct(rs.getLong("id"), rs.getString("name"),
                        rs.getBigDecimal("price"), rs.getBigDecimal("discounted_price"),
                        rs.getInt("minimum_order_quantity"), rs.getInt("available_quantity")))
                .optional();
    }

    public Optional<ExistingItem> findItem(long cartId, long productId) {
        return db.sql("""
                        SELECT id, quantity FROM consumer_cart_items
                        WHERE cart_id = :cartId AND product_id = :productId
                        """)
                .param("cartId", cartId)
                .param("productId", productId)
                .query((rs, n) -> new ExistingItem(rs.getLong("id"), rs.getInt("quantity")))
                .optional();
    }

    /** Re-writes the price as well as the quantity, in case the discount moved since. */
    public void updateItemQuantityAndPrice(long itemId, int quantity, BigDecimal unitPrice) {
        db.sql("""
                        UPDATE consumer_cart_items
                        SET quantity = :quantity, unit_price = :unitPrice, updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("quantity", quantity)
                .param("unitPrice", unitPrice)
                .param("id", itemId)
                .update();
    }

    /** Changes only the quantity, leaving the stored price frozen -- unlike the add path. */
    public void updateItemQuantity(long itemId, int quantity) {
        db.sql("""
                        UPDATE consumer_cart_items
                        SET quantity = :quantity, updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("quantity", quantity)
                .param("id", itemId)
                .update();
    }

    public void insertItem(long cartId, long productId, int quantity, BigDecimal unitPrice) {
        db.sql("""
                        INSERT INTO consumer_cart_items (cart_id, product_id, quantity, unit_price)
                        VALUES (:cartId, :productId, :quantity, :unitPrice)
                        """)
                .param("cartId", cartId)
                .param("productId", productId)
                .param("quantity", quantity)
                .param("unitPrice", unitPrice)
                .update();
    }

    public Optional<OwnedItem> findOwnedItem(long itemId, long consumerId) {
        return db.sql("""
                        SELECT ci.id, ci.quantity, ci.product_id,
                               p.available_quantity, p.minimum_order_quantity
                        FROM consumer_cart_items ci
                        JOIN consumer_carts c ON ci.cart_id = c.id
                        JOIN products p ON ci.product_id = p.id
                        WHERE ci.id = :itemId AND c.consumer_id = :consumerId
                        """)
                .param("itemId", itemId)
                .param("consumerId", consumerId)
                .query((rs, n) -> new OwnedItem(rs.getLong("id"), rs.getInt("quantity"),
                        rs.getLong("product_id"), rs.getInt("available_quantity"),
                        rs.getInt("minimum_order_quantity")))
                .optional();
    }

    public int deleteItem(long itemId, long consumerId) {
        return db.sql("""
                        DELETE FROM consumer_cart_items
                        WHERE id = :itemId
                          AND cart_id IN (SELECT id FROM consumer_carts WHERE consumer_id = :consumerId)
                        """)
                .param("itemId", itemId)
                .param("consumerId", consumerId)
                .update();
    }

    public void clearItems(long cartId) {
        db.sql("DELETE FROM consumer_cart_items WHERE cart_id = :cartId")
                .param("cartId", cartId)
                .update();
    }

    public record CartProduct(long id, String name, BigDecimal price, BigDecimal discountedPrice,
                              int minimumOrderQuantity, int availableQuantity) {

        /** The effective price. Falls back to the list price when no discount applies. */
        public BigDecimal effectivePrice() {
            return discountedPrice != null ? discountedPrice : price;
        }
    }

    public record ExistingItem(long id, int quantity) {
    }

    public record OwnedItem(long id, int quantity, long productId, int availableQuantity,
                            int minimumOrderQuantity) {
    }

    public record CheckoutItem(long productId, int quantity, BigDecimal unitPrice,
                               String productName) {
    }
}
