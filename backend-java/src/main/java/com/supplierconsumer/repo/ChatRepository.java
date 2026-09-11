package com.supplierconsumer.repo;

import com.supplierconsumer.wire.PgJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

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
}
