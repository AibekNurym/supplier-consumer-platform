package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/** The buyer identity table, entirely separate from the staff {@code users} table. */
@Repository
public class ConsumerRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public ConsumerRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public boolean emailExists(String email) {
        return db.sql("SELECT 1 FROM consumer_users WHERE email = :email")
                .param("email", email)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public Map<String, Object> insert(String email, String passwordHash, String firstName,
                                      String lastName, String phone) {
        return db.sql("""
                        INSERT INTO consumer_users (email, password_hash, first_name, last_name, phone)
                        VALUES (:email, :hash, :firstName, :lastName, :phone)
                        RETURNING id, email, first_name, last_name, phone, created_at
                        """)
                .param("email", email)
                .param("hash", passwordHash)
                .param("firstName", firstName)
                .param("lastName", lastName)
                .param("phone", phone)
                .query(pgJson.rowMapper())
                .single();
    }

    public Optional<ConsumerCredentials> findForLogin(String email) {
        return db.sql("""
                        SELECT id, email, password_hash, first_name, last_name, phone, is_active,
                               last_login, created_at
                        FROM consumer_users
                        WHERE email = :email AND is_active = true
                        """)
                .param("email", email)
                .query((rs, n) -> new ConsumerCredentials(
                        rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                        rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"),
                        rs.getBoolean("is_active"),
                        rs.getObject("last_login", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class)))
                .optional();
    }

    public Optional<Map<String, Object>> findProfile(long consumerId) {
        return db.sql("""
                        SELECT id, email, first_name, last_name, phone, is_active, last_login,
                               created_at
                        FROM consumer_users
                        WHERE id = :id
                        """)
                .param("id", consumerId)
                .query(pgJson.rowMapper())
                .optional();
    }

    public void touchLastLogin(long consumerId) {
        db.sql("UPDATE consumer_users SET last_login = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", consumerId)
                .update();
    }

    public record ConsumerCredentials(long id, String email, String passwordHash, String firstName,
                                      String lastName, String phone, boolean isActive,
                                      LocalDateTime lastLogin, LocalDateTime createdAt) {
    }
}
