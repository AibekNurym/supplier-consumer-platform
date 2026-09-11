package com.supplierconsumer.repo;

import com.supplierconsumer.security.Principals;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The lookups the authentication filters perform on every request.
 *
 * <p>A valid signature is not enough on its own: both filters re-read the account so that a
 * deactivated user stops being able to act immediately rather than when their 15-minute token
 * expires.
 */
@Repository
public class AuthRepository {

    private final JdbcClient db;

    public AuthRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<Principals.Company> findActiveCompanyUser(long userId) {
        return db.sql("""
                        SELECT u.id, u.email, u.first_name, u.last_name, u.role_id, u.company_id,
                               r.name AS role_name, r.description AS role_description
                        FROM users u
                        JOIN roles r ON u.role_id = r.id
                        WHERE u.id = :id AND u.is_active = true
                        """)
                .param("id", userId)
                .query((rs, n) -> new Principals.Company(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getInt("role_id"),
                        rs.getString("role_name"),
                        rs.getString("role_description"),
                        rs.getObject("company_id") == null ? null : rs.getLong("company_id"),
                        permissionsFor(rs.getInt("role_id"))))
                .optional();
    }

    /**
     * Permission names for a role. The Admin role deliberately has none -- administrative
     * authority comes from the role name alone, not from a permission grant.
     */
    public Set<String> permissionsFor(int roleId) {
        return new LinkedHashSet<>(db.sql("""
                        SELECT p.name
                        FROM role_permissions rp
                        JOIN permissions p ON rp.permission_id = p.id
                        WHERE rp.role_id = :roleId
                        ORDER BY p.name
                        """)
                .param("roleId", roleId)
                .query(String.class)
                .list());
    }

    public Optional<Principals.Consumer> findActiveConsumer(long consumerId) {
        return db.sql("""
                        SELECT id, email, first_name, last_name, phone
                        FROM consumer_users
                        WHERE id = :id AND is_active = true
                        """)
                .param("id", consumerId)
                .query((rs, n) -> new Principals.Consumer(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("phone")))
                .optional();
    }

    /**
     * The chat variant. Note the asymmetry carried over from the Node middleware: the company
     * branch filters on {@code is_active}, the consumer branch does not.
     */
    public Optional<Principals.Chat> findChatConsumer(long consumerId) {
        return db.sql("""
                        SELECT id, email, first_name, last_name
                        FROM consumer_users
                        WHERE id = :id
                        """)
                .param("id", consumerId)
                .query((rs, n) -> new Principals.Chat(
                        "consumer",
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        null,
                        null))
                .optional();
    }

    public Optional<Principals.Chat> findChatCompanyUser(long userId) {
        return db.sql("""
                        SELECT u.id, u.email, u.first_name, u.last_name, u.company_id,
                               r.name AS role_name
                        FROM users u
                        JOIN roles r ON u.role_id = r.id
                        WHERE u.id = :id AND u.is_active = true
                        """)
                .param("id", userId)
                .query((rs, n) -> new Principals.Chat(
                        "company",
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getObject("company_id") == null ? null : rs.getLong("company_id"),
                        rs.getString("role_name")))
                .optional();
    }
}
