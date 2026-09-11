package com.supplierconsumer.repo;

import com.supplierconsumer.auth.dto.AuthDtos.PermissionDetail;
import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Queries against {@code users}, {@code roles} and {@code permissions}.
 *
 * <p>Reads that feed a response go through {@link PgJson} so the driver's type mapping is
 * preserved; reads that feed a decision return typed records instead.
 */
@Repository
public class UserRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public UserRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public boolean emailExists(String email) {
        return db.sql("SELECT 1 FROM users WHERE email = :email")
                .param("email", email)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public Optional<RoleRow> findRoleByName(String name) {
        return db.sql("SELECT id, name, description FROM roles WHERE name = :name")
                .param("name", name)
                .query((rs, n) -> new RoleRow(rs.getInt("id"), rs.getString("name"),
                        rs.getString("description")))
                .optional();
    }

    /**
     * Permission rows as the response needs them. Ordered by name -- the original has no ORDER BY,
     * so the sequence was whatever Postgres happened to return; fixing an order costs nothing and
     * makes responses reproducible.
     */
    public List<PermissionDetail> permissionDetails(int roleId) {
        return db.sql("""
                        SELECT p.name, p.resource, p.action, p.description
                        FROM permissions p
                        JOIN role_permissions rp ON p.id = rp.permission_id
                        WHERE rp.role_id = :roleId
                        ORDER BY p.name
                        """)
                .param("roleId", roleId)
                .query((rs, n) -> new PermissionDetail(rs.getString("name"), rs.getString("resource"),
                        rs.getString("action"), rs.getString("description")))
                .list();
    }

    public List<String> permissionNames(int roleId) {
        return db.sql("""
                        SELECT p.name
                        FROM permissions p
                        JOIN role_permissions rp ON p.id = rp.permission_id
                        WHERE rp.role_id = :roleId
                        ORDER BY p.name
                        """)
                .param("roleId", roleId)
                .query(String.class)
                .list();
    }

    public long insertUser(String email, String passwordHash, String firstName, String lastName,
                           String phone, int roleId, Long createdBy) {
        KeyHolder keys = new GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO users (email, password_hash, first_name, last_name, phone,
                                           role_id, created_by)
                        VALUES (:email, :hash, :firstName, :lastName, :phone, :roleId, :createdBy)
                        """)
                .param("email", email)
                .param("hash", passwordHash)
                .param("firstName", firstName)
                .param("lastName", lastName)
                .param("phone", phone)
                .param("roleId", roleId)
                .param("createdBy", createdBy)
                .update(keys, "id");
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    public void setCompanyId(long userId, Long companyId) {
        db.sql("UPDATE users SET company_id = :companyId WHERE id = :id")
                .param("companyId", companyId)
                .param("id", userId)
                .update();
    }

    public void touchLastLogin(long userId) {
        db.sql("UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", userId)
                .update();
    }

    /**
     * The login lookup: the user with their role and, if they have one, their company's status.
     * The company columns drive the approval gate, which runs before the password is checked.
     */
    public Optional<LoginRow> findForLogin(String email) {
        return db.sql("""
                        SELECT u.id, u.email, u.password_hash, u.first_name, u.last_name, u.phone,
                               u.role_id, u.company_id, u.last_login,
                               r.name AS role_name, r.description AS role_description,
                               c.is_active AS company_active, c.status AS company_status,
                               c.rejection_message
                        FROM users u
                        JOIN roles r ON u.role_id = r.id
                        LEFT JOIN companies c ON u.company_id = c.id
                        WHERE u.email = :email AND u.is_active = true
                        """)
                .param("email", email)
                .query((rs, n) -> new LoginRow(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("phone"),
                        rs.getInt("role_id"),
                        rs.getObject("company_id") == null ? null : rs.getLong("company_id"),
                        rs.getObject("last_login", LocalDateTime.class),
                        rs.getString("role_name"),
                        rs.getString("role_description"),
                        rs.getObject("company_active") == null ? null : rs.getBoolean("company_active"),
                        rs.getString("company_status"),
                        rs.getString("rejection_message")))
                .optional();
    }

    public Optional<Map<String, Object>> findProfile(long userId) {
        return db.sql("""
                        SELECT u.id, u.email, u.first_name, u.last_name, u.phone, u.role_id,
                               u.company_id, u.is_active, u.last_login, u.created_at,
                               r.name AS role_name, r.description AS role_description
                        FROM users u
                        JOIN roles r ON u.role_id = r.id
                        WHERE u.id = :id
                        """)
                .param("id", userId)
                .query(pgJson.rowMapper())
                .optional();
    }

    /** Returns the raw updated row, which this endpoint sends back in snake_case. */
    public Map<String, Object> updateProfile(long userId, String firstName, String lastName, String phone) {
        return db.sql("""
                        UPDATE users
                        SET first_name = :firstName, last_name = :lastName, phone = :phone,
                            updated_at = NOW()
                        WHERE id = :id
                        RETURNING id, email, first_name, last_name, phone, updated_at
                        """)
                .param("firstName", firstName)
                .param("lastName", lastName)
                .param("phone", phone)
                .param("id", userId)
                .query(pgJson.rowMapper())
                .single();
    }

    /** The admin profile update, which may also change the email address. */
    public Map<String, Object> updateAdminProfile(long userId, String email, String firstName,
                                                  String lastName, String phone) {
        return db.sql("""
                        UPDATE users
                        SET email = :email, first_name = :firstName, last_name = :lastName,
                            phone = :phone, updated_at = NOW()
                        WHERE id = :id
                        RETURNING id, email, first_name, last_name, phone, updated_at
                        """)
                .param("email", email)
                .param("firstName", firstName)
                .param("lastName", lastName)
                .param("phone", phone)
                .param("id", userId)
                .query(pgJson.rowMapper())
                .single();
    }

    public Optional<String> findPasswordHash(long userId) {
        return db.sql("SELECT password_hash FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    public void updatePassword(long userId, String passwordHash) {
        db.sql("UPDATE users SET password_hash = :hash, updated_at = NOW() WHERE id = :id")
                .param("hash", passwordHash)
                .param("id", userId)
                .update();
    }

    public record RoleRow(int id, String name, String description) {
    }

    public record LoginRow(
            long id, String email, String passwordHash, String firstName, String lastName,
            String phone, int roleId, Long companyId, LocalDateTime lastLogin,
            String roleName, String roleDescription,
            Boolean companyActive, String companyStatus, String rejectionMessage) {
    }
}
