package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class CompanyRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public CompanyRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public long insertCompany(String name, String description, long ownerId) {
        KeyHolder keys = new GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO companies (name, description, owner_id)
                        VALUES (:name, :description, :ownerId)
                        """)
                .param("name", name)
                .param("description", description)
                .param("ownerId", ownerId)
                .update(keys, "id");
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    public boolean nameExists(String name) {
        return db.sql("SELECT 1 FROM companies WHERE name = :name")
                .param("name", name)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * Every company with its owner's contact details.
     *
     * <p>The handler is named {@code getPendingCompanies} but filters nothing -- the admin console
     * shows all statuses and groups them itself.
     */
    public List<Map<String, Object>> findAllWithOwners() {
        return db.sql("""
                        SELECT c.id, c.name, c.description, c.status, c.rejection_message,
                               c.business_documents, c.created_at,
                               u.email AS owner_email, u.first_name AS owner_first_name,
                               u.last_name AS owner_last_name, u.phone AS owner_phone
                        FROM companies c
                        JOIN users u ON c.owner_id = u.id
                        ORDER BY c.created_at DESC
                        """)
                .query(pgJson.rowMapper())
                .list();
    }

    public Optional<Map<String, Object>> findById(long companyId) {
        return db.sql("SELECT id, name, status, rejection_message FROM companies WHERE id = :id")
                .param("id", companyId)
                .query(pgJson.rowMapper())
                .optional();
    }

    public Map<String, Object> approve(long companyId) {
        return db.sql("""
                        UPDATE companies
                        SET status = 'approved', rejection_message = NULL, updated_at = NOW()
                        WHERE id = :id
                        RETURNING id, name, status
                        """)
                .param("id", companyId)
                .query(pgJson.rowMapper())
                .single();
    }

    public Map<String, Object> reject(long companyId, String rejectionMessage) {
        return db.sql("""
                        UPDATE companies
                        SET status = 'rejected', rejection_message = :message, updated_at = NOW()
                        WHERE id = :id
                        RETURNING id, name, status, rejection_message
                        """)
                .param("message", rejectionMessage)
                .param("id", companyId)
                .query(pgJson.rowMapper())
                .single();
    }

    /**
     * A hard delete, relied upon to cascade from companies through to the owner and everything
     * downstream. Only reachable for a rejected company.
     */
    public void delete(long companyId) {
        db.sql("DELETE FROM companies WHERE id = :id").param("id", companyId).update();
    }

    public void setBusinessDocuments(long companyId, String documentsJson) {
        db.sql("UPDATE companies SET business_documents = CAST(:docs AS jsonb) WHERE id = :id")
                .param("docs", documentsJson)
                .param("id", companyId)
                .update();
    }

    /** Deactivates a company and everyone in it. Data is retained; only access is withdrawn. */
    public void deactivate(long companyId) {
        db.sql("UPDATE companies SET is_active = false, updated_at = NOW() WHERE id = :id")
                .param("id", companyId)
                .update();
        db.sql("UPDATE users SET is_active = false, updated_at = NOW() WHERE company_id = :id")
                .param("id", companyId)
                .update();
    }

    public Optional<Boolean> isActive(long companyId) {
        return db.sql("SELECT is_active FROM companies WHERE id = :id")
                .param("id", companyId)
                .query(Boolean.class)
                .optional();
    }
}
