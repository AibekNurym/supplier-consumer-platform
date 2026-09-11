package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chat messages between a buyer and a supplier.
 *
 * <p>A conversation is implicit in the {@code (consumer_id, company_id)} pair -- there is no
 * conversations table. {@code sender_id} is polymorphic, pointing at either identity table
 * depending on {@code sender_type}, which is why it carries no foreign key and why the sender's
 * name has to be resolved with a pair of conditional joins.
 */
@Repository
public class ChatRepository {

    private final JdbcClient db;
    private final PgJson pgJson;

    public ChatRepository(JdbcClient db, PgJson pgJson) {
        this.db = db;
        this.pgJson = pgJson;
    }

    public long insertMessage(long consumerId, long companyId, String senderType, long senderId,
                              String messageText, String messageType, String attachmentUrl,
                              String attachmentName, Long replyToMessageId, Long productId) {
        KeyHolder keys = new GeneratedKeyHolder();
        db.sql("""
                        INSERT INTO consumer_company_messages
                            (consumer_id, company_id, sender_type, sender_id, message_text,
                             message_type, attachment_url, attachment_name, reply_to_message_id,
                             product_id)
                        VALUES (:consumerId, :companyId, :senderType, :senderId, :messageText,
                                :messageType, :attachmentUrl, :attachmentName, :replyTo, :productId)
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .param("senderType", senderType)
                .param("senderId", senderId)
                .param("messageText", messageText)
                .param("messageType", messageType)
                .param("attachmentUrl", attachmentUrl)
                .param("attachmentName", attachmentName)
                .param("replyTo", replyToMessageId)
                .param("productId", productId)
                .update(keys, "id");
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    /**
     * Re-reads a message with its sender's name resolved.
     *
     * <p>This projection is both the HTTP response body and the realtime payload, so the two are
     * guaranteed identical. Note it includes {@code consumer_id} and {@code company_id}, which the
     * message-list query omits -- the two endpoints return genuinely different shapes for the same
     * row.
     */
    public Optional<Map<String, Object>> findHydrated(long messageId) {
        return db.sql("""
                        SELECT m.id, m.consumer_id, m.company_id, m.sender_type, m.sender_id,
                               m.message_text, m.message_type, m.attachment_url, m.attachment_name,
                               m.reply_to_message_id, m.product_id, m.sent_at, m.read_at,
                               CASE WHEN m.sender_type = 'consumer'
                                         THEN cu.first_name || ' ' || cu.last_name
                                    WHEN m.sender_type = 'company'
                                         THEN u.first_name || ' ' || u.last_name
                                    ELSE NULL END AS sender_name
                        FROM consumer_company_messages m
                        LEFT JOIN consumer_users cu
                          ON m.sender_type = 'consumer' AND m.sender_id = cu.id
                        LEFT JOIN users u
                          ON m.sender_type = 'company' AND m.sender_id = u.id
                        WHERE m.id = :id
                        LIMIT 1
                        """)
                .param("id", messageId)
                .query(pgJson.rowMapper())
                .optional();
    }

    /**
     * Every message in a conversation, with the replied-to message and any linked product
     * hydrated inline so the client can render reply quotes and product cards without extra calls.
     *
     * <p>This projection is a superset of the one returned when a message is sent, except that it
     * omits consumer_id and company_id -- the two endpoints genuinely return different shapes for
     * the same row.
     */
    public List<Map<String, Object>> findMessages(long consumerId, long companyId) {
        return db.sql("""
                        SELECT m.id, m.sender_type, m.sender_id, m.message_text, m.message_type,
                               m.attachment_url, m.attachment_name, m.reply_to_message_id,
                               rm.message_text AS reply_to_message_text,
                               rm.attachment_name AS reply_to_attachment_name,
                               m.product_id,
                               p.name AS product_name, p.image AS product_image,
                               p.price AS product_price,
                               p.discount_percentage AS product_discount_percentage,
                               CASE WHEN p.discount_percentage > 0
                                    THEN ROUND(p.price * (1 - p.discount_percentage / 100), 2)
                                    ELSE p.price END AS product_discounted_price,
                               p.available_quantity AS product_available_quantity,
                               p.minimum_order_quantity AS product_minimum_order_quantity,
                               m.sent_at, m.read_at,
                               CASE WHEN m.sender_type = 'consumer'
                                         THEN cu.first_name || ' ' || cu.last_name
                                    WHEN m.sender_type = 'company'
                                         THEN u.first_name || ' ' || u.last_name
                               END AS sender_name
                        FROM consumer_company_messages m
                        LEFT JOIN consumer_users cu
                          ON m.sender_type = 'consumer' AND m.sender_id = cu.id
                        LEFT JOIN users u
                          ON m.sender_type = 'company' AND m.sender_id = u.id
                        LEFT JOIN consumer_company_messages rm ON rm.id = m.reply_to_message_id
                        LEFT JOIN products p ON m.product_id = p.id
                        WHERE m.consumer_id = :consumerId AND m.company_id = :companyId
                        ORDER BY m.sent_at ASC
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    /** Marks everything the reader did not send as read, and reports how many rows changed. */
    public int markRead(long consumerId, long companyId, String readerType) {
        return db.sql("""
                        UPDATE consumer_company_messages
                        SET read_at = CURRENT_TIMESTAMP
                        WHERE consumer_id = :consumerId AND company_id = :companyId
                          AND sender_type != :readerType AND read_at IS NULL
                        """)
                .param("consumerId", consumerId)
                .param("companyId", companyId)
                .param("readerType", readerType)
                .update();
    }

    /**
     * The buyer's conversation list. unread_count is computed from read_at here, but the caller
     * overlays whatever the Redis counter holds, so the two can disagree -- there are genuinely
     * two sources of truth for "unread".
     */
    public List<Map<String, Object>> findConversationsForConsumer(long consumerId) {
        return db.sql("""
                        SELECT DISTINCT m.company_id,
                               c.name AS company_name, c.description AS company_description,
                               (SELECT message_text FROM consumer_company_messages
                                 WHERE consumer_id = :consumerId AND company_id = m.company_id
                                 ORDER BY sent_at DESC LIMIT 1) AS last_message,
                               (SELECT sent_at FROM consumer_company_messages
                                 WHERE consumer_id = :consumerId AND company_id = m.company_id
                                 ORDER BY sent_at DESC LIMIT 1) AS last_message_time,
                               (SELECT COUNT(*)::INT FROM consumer_company_messages
                                 WHERE consumer_id = :consumerId AND company_id = m.company_id
                                   AND sender_type = 'company' AND read_at IS NULL) AS unread_count
                        FROM consumer_company_messages m
                        JOIN companies c ON m.company_id = c.id
                        WHERE m.consumer_id = :consumerId
                        GROUP BY m.company_id, c.name, c.description
                        ORDER BY last_message_time DESC NULLS LAST
                        """)
                .param("consumerId", consumerId)
                .query(pgJson.rowMapper())
                .list();
    }

    public List<Map<String, Object>> findConversationsForCompany(long companyId) {
        return db.sql("""
                        SELECT DISTINCT m.consumer_id,
                               cu.first_name || ' ' || cu.last_name AS consumer_name,
                               cu.email AS consumer_email,
                               (SELECT message_text FROM consumer_company_messages
                                 WHERE consumer_id = m.consumer_id AND company_id = :companyId
                                 ORDER BY sent_at DESC LIMIT 1) AS last_message,
                               (SELECT sent_at FROM consumer_company_messages
                                 WHERE consumer_id = m.consumer_id AND company_id = :companyId
                                 ORDER BY sent_at DESC LIMIT 1) AS last_message_time,
                               (SELECT COUNT(*)::INT FROM consumer_company_messages
                                 WHERE consumer_id = m.consumer_id AND company_id = :companyId
                                   AND sender_type = 'consumer' AND read_at IS NULL) AS unread_count
                        FROM consumer_company_messages m
                        JOIN consumer_users cu ON m.consumer_id = cu.id
                        WHERE m.company_id = :companyId
                        GROUP BY m.consumer_id, cu.first_name, cu.last_name, cu.email
                        ORDER BY last_message_time DESC NULLS LAST
                        """)
                .param("companyId", companyId)
                .query(pgJson.rowMapper())
                .list();
    }

    public boolean productBelongsToCompany(long productId, long companyId) {
        return db.sql("SELECT 1 FROM products WHERE id = :productId AND company_id = :companyId")
                .param("productId", productId)
                .param("companyId", companyId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }
}
