import { sql } from "../config/db.js";

/**
 * Inserts a chat message into consumer_company_messages and returns the hydrated record.
 *
 * @param {object} params
 * @param {number} params.consumerId
 * @param {number} params.companyId
 * @param {'consumer'|'company'} params.senderType
 * @param {number} params.senderId
 * @param {string} params.messageText
 * @param {string} [params.messageType='text']
 * @param {string|null} [params.attachmentUrl=null]
 * @param {string|null} [params.attachmentName=null]
 * @param {number|null} [params.replyToMessageId=null]
 * @param {number|null} [params.productId=null]
 * @param {object} [options]
 * @param {Function} [options.db=sql] - Tagged template executor (sql or transaction)
 * @returns {Promise<object|null>}
 */
export const insertChatMessage = async (
  {
    consumerId,
    companyId,
    senderType,
    senderId,
    messageText,
    messageType = "text",
    attachmentUrl = null,
    attachmentName = null,
    replyToMessageId = null,
    productId = null,
  },
  {
    db = sql,
  } = {}
) => {
  if (!consumerId || !companyId || !senderType || !senderId) {
    throw new Error("Missing required fields for chat message insertion");
  }

  const trimmedMessage = typeof messageText === "string"
    ? messageText.trim()
    : "";

  if (!trimmedMessage && !attachmentUrl && !attachmentName && !productId) {
    throw new Error("Chat message text cannot be empty when no attachment or product is provided");
  }

  const [inserted] = await db`
    INSERT INTO consumer_company_messages (
      consumer_id,
      company_id,
      sender_type,
      sender_id,
      message_text,
      message_type,
      attachment_url,
      attachment_name,
      reply_to_message_id,
      product_id
    )
    VALUES (
      ${consumerId},
      ${companyId},
      ${senderType},
      ${senderId},
      ${trimmedMessage},
      ${messageType},
      ${attachmentUrl},
      ${attachmentName},
      ${replyToMessageId},
      ${productId}
    )
    RETURNING id
  `;

  if (!inserted?.id) {
    return null;
  }

  const [hydrated] = await db`
    SELECT 
      m.id,
      m.consumer_id,
      m.company_id,
      m.sender_type,
      m.sender_id,
      m.message_text,
      m.message_type,
      m.attachment_url,
      m.attachment_name,
      m.reply_to_message_id,
      m.product_id,
      m.sent_at,
      m.read_at,
      CASE 
        WHEN m.sender_type = 'consumer' THEN cu.first_name || ' ' || cu.last_name
        WHEN m.sender_type = 'company' THEN u.first_name || ' ' || u.last_name
        ELSE NULL
      END AS sender_name
    FROM consumer_company_messages m
    LEFT JOIN consumer_users cu ON m.sender_type = 'consumer' AND m.sender_id = cu.id
    LEFT JOIN users u ON m.sender_type = 'company' AND m.sender_id = u.id
    WHERE m.id = ${inserted.id}
    LIMIT 1
  `;

  return hydrated || null;
};

