package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Staff administration: the user list, role catalogue and audit trail the console shows. */
@Repository
public class UserManagementRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public UserManagementRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public List<Map<String, Object>> findAllForCompany(long companyId) {
        return db.sql("""
                        SELECT u.id, u.email, u.first_name, u.last_name, u.phone,
                               u.is_active, u.last_login, u.created_at, u.updated_at,
                               r.name AS role_name, r.description AS role_description,
                               c.name AS company_name,
                               creator.first_name AS created_by_first_name,
                               creator.last_name AS created_by_last_name
                        FROM users u
                        JOIN roles r ON u.role_id = r.id
                        LEFT JOIN companies c ON u.company_id = c.id
                        LEFT JOIN users creator ON u.created_by = creator.id
                        WHERE u.company_id = :companyId
                        ORDER BY u.created_at DESC
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    /**
     * A single user, scoped to the caller's company so that another tenant's id yields 404 rather
     * than 403.
     *
     * <p>The original selects {@code u.*}, which puts {@code password_hash} in the response. The
     * columns are listed explicitly here so it cannot be.
     */
    public Optional<Map<String, Object>> findForCompany(long userId, long companyId) {
        return db.sql("""
                        SELECT u.id, u.email, u.first_name, u.last_name, u.phone, u.role_id,
                               u.is_active, u.last_login, u.last_active, u.company_id,
                               u.created_by, u.created_at, u.updated_at,
                               r.name AS role_name, r.description AS role_description,
                               creator.first_name AS created_by_first_name,
                               creator.last_name AS created_by_last_name
                        FROM users u
                        JOIN roles r ON u.role_id = r.id
                        LEFT JOIN users creator ON u.created_by = creator.id
                        WHERE u.id = :id AND u.company_id = :companyId
                        """)
                .param("id", userId)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .optional();
    }

    /** The values the update needs, plus the role name the hierarchy rules are checked against. */
    public Optional<ExistingUser> findExisting(long userId, long companyId) {
        return db.sql("""
                        SELECT u.id, u.email, u.password_hash, u.first_name, u.last_name, u.phone,
                               u.role_id, u.is_active, r.name AS role_name
                        FROM users u
                        JOIN roles r ON u.role_id = r.id
                        WHERE u.id = :id AND u.company_id = :companyId
                        """)
                .param("id", userId)
                .param("companyId", companyId)
                .query((rs, n) -> new ExistingUser(
                        rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                        rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"),
                        rs.getInt("role_id"), rs.getBoolean("is_active"), rs.getString("role_name")))
                .optional();
    }

    /**
     * The role catalogue with a headcount per role.
     *
     * <p>Deliberately not company-scoped, matching the original: the count spans every company on
     * the platform. {@code user_count} is a bare {@code COUNT(*)}, so it reaches clients as a
     * string while most other counts in this API are numbers.
     */
    public List<Map<String, Object>> findAllRoles() {
        return db.sql("""
                        SELECT r.id, r.name, r.description, r.created_at, r.updated_at,
                               COUNT(u.id) AS user_count
                        FROM roles r
                        LEFT JOIN users u ON r.id = u.role_id AND u.is_active = true
                        GROUP BY r.id, r.name, r.description, r.created_at, r.updated_at
                        ORDER BY r.name
                        """)
                .query(pgJson.rowMapper())
                .list();
    }

    public List<Map<String, Object>> findRolePermissions(long roleId) {
        return db.sql("""
                        SELECT p.id, p.name, p.resource, p.action, p.description, p.created_at
                        FROM permissions p
                        JOIN role_permissions rp ON p.id = rp.permission_id
                        WHERE rp.role_id = :roleId
                        ORDER BY p.resource, p.action
                        """)
                .param("roleId", roleId)
                .query(pgJson.rowMapper())
                .list();
    }

    public Map<String, Object> insert(String email, String passwordHash, String firstName,
                                      String lastName, String phone, int roleId, long companyId,
                                      long createdBy) {
        return db.sql("""
                        INSERT INTO users (email, password_hash, first_name, last_name, phone,
                                           role_id, company_id, created_by)
                        VALUES (:email, :hash, :firstName, :lastName, :phone, :roleId, :companyId,
                                :createdBy)
                        RETURNING id, email, first_name, last_name, phone, role_id, company_id,
                                  created_at
                        """)
                .param("email", email)
                .param("hash", passwordHash)
                .param("firstName", firstName)
                .param("lastName", lastName)
                .param("phone", phone)
                .param("roleId", roleId)
                .param("companyId", companyId)
                .param("createdBy", createdBy)
                .query(pgJson.rowMapper())
                .single();
    }

    public Map<String, Object> update(long userId, String firstName, String lastName, String phone,
                                      int roleId, String passwordHash, boolean isActive) {
        return db.sql("""
                        UPDATE users
                        SET first_name = :firstName, last_name = :lastName, phone = :phone,
                            role_id = :roleId, password_hash = :hash, is_active = :isActive,
                            updated_at = NOW()
                        WHERE id = :id
                        RETURNING id, email, first_name, last_name, phone, role_id, is_active,
                                  updated_at
                        """)
                .param("firstName", firstName)
                .param("lastName", lastName)
                .param("phone", phone)
                .param("roleId", roleId)
                .param("hash", passwordHash)
                .param("isActive", isActive)
                .param("id", userId)
                .query(pgJson.rowMapper())
                .single();
    }

    public void delete(long userId) {
        db.sql("DELETE FROM users WHERE id = :id").param("id", userId).update();
    }

    /**
     * A page of the audit trail.
     *
     * <p>Scoped to the caller's company. The original filters only on the optional query
     * parameters, starting its WHERE clause at {@code 1=1}, so an Owner or Manager of one company
     * can read every other company's audit entries -- including their staff names and email
     * addresses. That is a cross-tenant disclosure of the same kind as returning a password hash,
     * so the company filter is applied here.
     */
    public AuditPage findAuditLog(long companyId, Long userId, String action, String resource,
                                  int limit, int offset) {
        StringBuilder where = new StringBuilder("""
                (al.user_id IS NULL OR al.user_id IN (SELECT id FROM users WHERE company_id = :companyId))
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("companyId", companyId);

        if (userId != null) {
            where.append(" AND al.user_id = :userId");
            params.put("userId", userId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND al.action = :action");
            params.put("action", action);
        }
        if (resource != null && !resource.isBlank()) {
            where.append(" AND al.resource = :resource");
            params.put("resource", resource);
        }

        var logsQuery = db.sql("""
                SELECT al.id, al.user_id, al.action, al.resource, al.resource_id, al.details,
                       al.ip_address, al.user_agent, al.created_at,
                       u.first_name AS user_first_name, u.last_name AS user_last_name,
                       u.email AS user_email
                FROM audit_log al
                LEFT JOIN users u ON al.user_id = u.id
                WHERE %s
                ORDER BY al.created_at DESC
                LIMIT :limit OFFSET :offset
                """.formatted(where));
        params.forEach(logsQuery::param);
        List<Map<String, Object>> logs = new ArrayList<>(logsQuery
                .param("limit", limit)
                .param("offset", offset)
                .query(pgJson.rowMapper())
                .list());

        var countQuery = db.sql("SELECT COUNT(*)::INT FROM audit_log al WHERE %s".formatted(where));
        params.forEach(countQuery::param);
        Integer total = countQuery.query(Integer.class).single();

        return new AuditPage(logs, total == null ? 0 : total);
    }

    public record ExistingUser(long id, String email, String passwordHash, String firstName,
                               String lastName, String phone, int roleId, boolean isActive,
                               String roleName) {
    }

    public record AuditPage(List<Map<String, Object>> logs, int total) {
    }
}
