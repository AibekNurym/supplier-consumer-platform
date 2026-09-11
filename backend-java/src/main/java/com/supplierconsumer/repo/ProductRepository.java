package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Product queries.
 *
 * <p>Every method takes a {@code companyId} and applies it. That is not politeness: this is the
 * multi-tenancy boundary, and in the original it is a {@code WHERE company_id = ?} typed out by
 * hand at each of thirty-odd call sites. Making it a required parameter means a query cannot
 * accidentally be written without it.
 */
@Repository
public class ProductRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public ProductRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public List<Map<String, Object>> findAllForCompany(long companyId) {
        return db.sql("""
                        SELECT id, name, image, price, discount_percentage, lead_time_days,
                               minimum_order_quantity, available_quantity, company_id, created_at,
                               updated_at
                        FROM products
                        WHERE company_id = :companyId
                        ORDER BY created_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public Optional<Map<String, Object>> findForCompany(long id, long companyId) {
        return db.sql("""
                        SELECT id, name, image, price, discount_percentage, lead_time_days,
                               minimum_order_quantity, available_quantity, company_id, created_at,
                               updated_at
                        FROM products
                        WHERE id = :id AND company_id = :companyId
                        """)
                .param("id", id)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .optional();
    }

    public Map<String, Object> insert(String name, String image, BigDecimal price,
                                      BigDecimal discountPercentage, int leadTimeDays,
                                      int minimumOrderQuantity, int availableQuantity, long companyId) {
        return db.sql("""
                        INSERT INTO products (name, image, price, discount_percentage, lead_time_days,
                                              minimum_order_quantity, available_quantity, company_id)
                        VALUES (:name, :image, :price, :discount, :leadTime, :moq, :available, :companyId)
                        RETURNING id, name, image, price, discount_percentage, lead_time_days,
                                  minimum_order_quantity, available_quantity, company_id, created_at,
                                  updated_at
                        """)
                .param("name", name)
                .param("image", image)
                .param("price", price)
                .param("discount", discountPercentage)
                .param("leadTime", leadTimeDays)
                .param("moq", minimumOrderQuantity)
                .param("available", availableQuantity)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .single();
    }

    public Optional<Map<String, Object>> update(long id, long companyId, String name, String image,
                                                BigDecimal price, BigDecimal discountPercentage,
                                                Integer leadTimeDays, Integer minimumOrderQuantity,
                                                Integer availableQuantity) {
        return db.sql("""
                        UPDATE products
                        SET name = :name, image = :image, price = :price,
                            discount_percentage = :discount, lead_time_days = :leadTime,
                            minimum_order_quantity = :moq, available_quantity = :available,
                            updated_at = NOW()
                        WHERE id = :id AND company_id = :companyId
                        RETURNING id, name, image, price, discount_percentage, lead_time_days,
                                  minimum_order_quantity, available_quantity, company_id, created_at,
                                  updated_at
                        """)
                .param("name", name)
                .param("image", image)
                .param("price", price)
                .param("discount", discountPercentage)
                .param("leadTime", leadTimeDays)
                .param("moq", minimumOrderQuantity)
                .param("available", availableQuantity)
                .param("id", id)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .optional();
    }

    /**
     * How many live orders reference this product. Deleting it would cascade the cart rows away
     * and null out the order-item links, so the handler refuses while any order is still open.
     */
    public int countActiveOrders(long productId) {
        Integer count = db.sql("""
                        SELECT count(*)::INT
                        FROM consumer_order_items oi
                        JOIN consumer_orders o ON oi.order_id = o.id
                        WHERE oi.product_id = :productId
                          AND o.status IN ('pending', 'accepted', 'in_progress')
                        """)
                .param("productId", productId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    public Optional<Map<String, Object>> delete(long id, long companyId) {
        return db.sql("""
                        DELETE FROM products
                        WHERE id = :id AND company_id = :companyId
                        RETURNING id, name, image, price, discount_percentage, lead_time_days,
                                  minimum_order_quantity, available_quantity, company_id, created_at,
                                  updated_at
                        """)
                .param("id", id)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .optional();
    }
}
