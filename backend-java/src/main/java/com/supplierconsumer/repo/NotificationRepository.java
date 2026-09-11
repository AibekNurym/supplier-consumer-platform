package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * The notification feed.
 *
 * <p>Fully polymorphic and free of foreign keys: {@code target_type} decides which of
 * {@code target_consumer_id} and {@code target_company_id} is meaningful.
 *
 * <p>A company notification targets the <em>company</em>, not an individual employee, so there is
 * no per-user read state -- once one member of staff reads it, it is read for everyone. That is
 * preserved, since changing it would change what users see.
 */
@Repository
public class NotificationRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public NotificationRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public Map<String, Object> insert(String targetType, Long consumerId, Long companyId,
                                      String title, String body, String actionType,
                                      String actionPayloadJson) {
        return db.sql("""
                        INSERT INTO notifications (target_type, target_consumer_id, target_company_id,
                                                   title, body, action_type, action_payload)
                        VALUES (:targetType, :consumerId, :companyId, :title, :body, :actionType,
                                CAST(:payload AS jsonb))
                        RETURNING id, target_type, target_consumer_id, target_company_id, title,
                                  body, action_type, action_payload, created_at, read_at
                        """)
                .param("targetType", targetType)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .param("title", title)
                .param("body", body)
                .param("actionType", actionType)
                .param("payload", actionPayloadJson == null ? "{}" : actionPayloadJson)
                .query(pgJson.rowMapper())
                .single();
    }

    /**
     * The cast to INT matters: it makes the count a JSON number. A bare {@code COUNT(*)} would
     * reach clients as a string, which is what happens elsewhere in this API.
     */
    public int unreadCountForConsumer(long consumerId) {
        Integer count = db.sql("""
                        SELECT COUNT(*)::INT FROM notifications
                        WHERE target_type = 'consumer' AND target_consumer_id = :id
                          AND read_at IS NULL
                        """)
                .param("id", consumerId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    public int unreadCountForCompany(long companyId) {
        Integer count = db.sql("""
                        SELECT COUNT(*)::INT FROM notifications
                        WHERE target_type = 'company' AND target_company_id = :id
                          AND read_at IS NULL
                        """)
                .param("id", companyId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> findForConsumer(long consumerId, int limit) {
        return db.sql("""
                        SELECT id, target_type, target_consumer_id, target_company_id, title, body,
                               action_type, action_payload, created_at, read_at
                        FROM notifications
                        WHERE target_type = 'consumer' AND target_consumer_id = :id
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .param("id", consumerId)
                .param("limit", limit)
                .query(pgJson.rowMapper())
                .list();
    }

    public List<Map<String, Object>> findForCompany(long companyId, int limit) {
        return db.sql("""
                        SELECT id, target_type, target_consumer_id, target_company_id, title, body,
                               action_type, action_payload, created_at, read_at
                        FROM notifications
                        WHERE target_type = 'company' AND target_company_id = :id
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .param("id", companyId)
                .param("limit", limit)
                .query(pgJson.rowMapper())
                .list();
    }

    /**
     * Marks notifications read. The target predicate is part of the statement, so a caller can
     * only ever mark their own -- that is the ownership check.
     */
    public int markReadForConsumer(long consumerId, List<Long> ids) {
        return db.sql("""
                        UPDATE notifications
                        SET read_at = NOW()
                        WHERE target_type = 'consumer' AND target_consumer_id = :id
                          AND read_at IS NULL AND id IN (:ids)
                        """)
                .param("id", consumerId)
                .param("ids", ids)
                .update();
    }

    public int markReadForCompany(long companyId, List<Long> ids) {
        return db.sql("""
                        UPDATE notifications
                        SET read_at = NOW()
                        WHERE target_type = 'company' AND target_company_id = :id
                          AND read_at IS NULL AND id IN (:ids)
                        """)
                .param("id", companyId)
                .param("ids", ids)
                .update();
    }
}
