import { sql } from "../config/db.js";
import { insertChatMessage } from "../services/chatMessageService.js";
import { emitChatMessage } from "../realtime/events.js";
import { createNotification } from "../services/notificationService.js";
import {
  clearUnreadCount,
  getCompanyUnreadSnapshot,
  getConsumerUnreadSnapshot,
  incrementUnreadCount,
} from "../services/chatUnreadService.js";

// Get chat messages between a consumer and a company
export const getChatMessages = async (req, res) => {
  try {
    const { consumerId, companyId } = req.params;
    const user = req.user;
    
    // Verify that the user has permission to view this chat
    // For consumers: must be the consumer themselves
    // For company employees: must be from the same company
    if (user.type === 'consumer') {
      if (parseInt(user.id) !== parseInt(consumerId)) {
        return res.status(403).json({ success: false, message: 'Unauthorized' });
      }
    } else if (user.type === 'company') {
      if (parseInt(user.company_id) !== parseInt(companyId)) {
        return res.status(403).json({ success: false, message: 'Unauthorized' });
      }
    } else {
      return res.status(403).json({ success: false, message: 'Unauthorized' });
    }
    
    // Fetch messages between consumer and company
    const messages = await sql`
      SELECT 
        m.id,
        m.sender_type,
        m.sender_id,
        m.message_text,
        m.message_type,
        m.attachment_url,
        m.attachment_name,
        m.reply_to_message_id,
        rm.message_text AS reply_to_message_text,
        rm.attachment_name AS reply_to_attachment_name,
        m.product_id,
        p.name AS product_name,
        p.image AS product_image,
        p.price AS product_price,
        p.discount_percentage AS product_discount_percentage,
        CASE 
          WHEN p.discount_percentage > 0 THEN 
            ROUND(p.price * (1 - p.discount_percentage / 100), 2)
          ELSE 
            p.price
        END AS product_discounted_price,
        p.available_quantity AS product_available_quantity,
        p.minimum_order_quantity AS product_minimum_order_quantity,
        m.sent_at,
        m.read_at,
        CASE 
          WHEN m.sender_type = 'consumer' THEN cu.first_name || ' ' || cu.last_name
          WHEN m.sender_type = 'company' THEN u.first_name || ' ' || u.last_name
        END as sender_name
      FROM consumer_company_messages m
      LEFT JOIN consumer_users cu ON m.sender_type = 'consumer' AND m.sender_id = cu.id
      LEFT JOIN users u ON m.sender_type = 'company' AND m.sender_id = u.id
      LEFT JOIN consumer_company_messages rm ON rm.id = m.reply_to_message_id
      LEFT JOIN products p ON m.product_id = p.id
      WHERE m.consumer_id = ${consumerId} AND m.company_id = ${companyId}
      ORDER BY m.sent_at ASC
    `;
    
    return res.json({
      success: true,
      data: messages
    });
  } catch (error) {
    console.error('Error fetching chat messages:', error);
    return res.status(500).json({ success: false, message: 'Failed to fetch messages', error: error.message });
  }
};

// Send a message from consumer to company or vice versa
export const sendMessage = async (req, res) => {
  try {
    console.log('=== SEND MESSAGE CONTROLLER DEBUG ===');
    console.log('req.body:', req.body);
    console.log('req.params:', req.params);
    
    const { consumerId, companyId } = req.params;
    const { message_text, message_type = 'text', attachment_url, attachment_name, reply_to_message_id, product_id } = req.body;
    const user = req.user;
    
    console.log('Extracted values:', { message_text, message_type, attachment_url, attachment_name, product_id });
    
    // Message can be empty if it has an attachment or product link
    const messageText = message_text || '';
    const trimmedMessage = typeof messageText === 'string' ? messageText.trim() : '';
    
    if (trimmedMessage === '' && !attachment_url && !product_id) {
      console.log('Error: Message cannot be empty');
      return res.status(400).json({ success: false, message: 'Message cannot be empty' });
    }

    // Validate product_id if provided (must belong to the company)
    if (product_id) {
      const productCheck = await sql`
        SELECT id, name FROM products 
        WHERE id = ${product_id} AND company_id = ${companyId}
      `;
      
      if (productCheck.length === 0) {
        return res.status(400).json({ 
          success: false, 
          message: 'Product not found or does not belong to this company' 
        });
      }
    }
    
    // Determine sender type and ID
    let senderType, senderId;
    if (user.type === 'consumer') {
      senderType = 'consumer';
      senderId = Number(user.id);
    } else if (user.type === 'company') {
      senderType = 'company';
      senderId = Number(user.id);
    } else {
      return res.status(403).json({ success: false, message: 'Unauthorized user type' });
    }

    if (!Number.isFinite(senderId)) {
      console.log('Error: Invalid sender id', user.id);
      return res.status(400).json({ success: false, message: 'Invalid sender identifier' });
    }
    
    // Verify that consumer has approved access to the company
    const accessCheck = await sql`
      SELECT is_active 
      FROM consumer_company_access 
      WHERE consumer_id = ${consumerId} AND company_id = ${companyId} AND is_active = true
    `;
    
    if (accessCheck.length === 0) {
      return res.status(403).json({ 
        success: false, 
        message: 'You must have approved access to message this company' 
      });
    }

    const blockCheck = await sql`
      SELECT id FROM company_consumer_blocks
      WHERE company_id = ${companyId}
        AND consumer_id = ${consumerId}
        AND is_blocked = TRUE
    `;

    if (blockCheck.length > 0) {
      return res.status(403).json({
        success: false,
        message: "Messaging is disabled because this consumer is blocked."
      });
    }
    
    // Insert message
    const numericConsumerId = Number(consumerId);
    const numericCompanyId = Number(companyId);

    if (!Number.isFinite(numericConsumerId) || !Number.isFinite(numericCompanyId)) {
      console.log('Error: Invalid conversation identifiers', { consumerId, companyId });
      return res.status(400).json({ success: false, message: 'Invalid conversation identifiers' });
    }

    const message = await insertChatMessage(
      {
        consumerId: numericConsumerId,
        companyId: numericCompanyId,
        senderType,
        senderId,
        messageText: trimmedMessage,
        messageType: message_type,
        attachmentUrl: attachment_url || null,
        attachmentName: attachment_name || null,
        replyToMessageId: reply_to_message_id ? Number(reply_to_message_id) : null,
        productId: product_id ? Number(product_id) : null,
      },
      { db: sql }
    );

    if (!message) {
      console.log("Error: Failed to insert chat message");
      return res.status(500).json({ success: false, message: "Failed to send message" });
    }
    
    emitChatMessage(message);
    await incrementUnreadCount({
      consumerId: numericConsumerId,
      companyId: numericCompanyId,
      senderType,
    });

    const previewText =
      message.message_text?.trim() ||
      (message.message_type === "audio"
        ? "Sent a voice message"
        : message.message_type === "image"
        ? "Sent an image"
        : message.message_type === "file"
        ? "Sent a file"
        : "Sent a message");

    if (senderType === "consumer") {
      const displayName =
        (req.user?.firstName && req.user?.lastName
          ? `${req.user.firstName} ${req.user.lastName}`.trim()
          : req.user?.email) || "Consumer";
      await createNotification({
        targetType: "company",
        companyId: numericCompanyId,
        title: `New message from ${displayName}`,
        body: previewText,
        actionType: "chat:message",
        actionPayload: {
          consumerId: numericConsumerId,
          companyId: numericCompanyId,
          messageId: message.id,
        },
      });
    } else if (senderType === "company") {
      const displayName =
        (req.user?.firstName && req.user?.lastName
          ? `${req.user.firstName} ${req.user.lastName}`.trim()
          : req.user?.email) || "Company representative";
      await createNotification({
        targetType: "consumer",
        consumerId: numericConsumerId,
        title: `New message from ${displayName}`,
        body: previewText,
        actionType: "chat:message",
        actionPayload: {
          consumerId: numericConsumerId,
          companyId: numericCompanyId,
          messageId: message.id,
        },
      });
    }
    
    return res.json({
      success: true,
      data: message
    });
  } catch (error) {
    console.error('Error sending message:', error);
    return res.status(500).json({ success: false, message: 'Failed to send message', error: error.message });
  }
};

// Mark messages as read
export const markMessagesAsRead = async (req, res) => {
  try {
    const { consumerId, companyId } = req.params;
    const user = req.user;
    
    // Update read_at for messages not sent by the current user
    const numericConsumerId = Number(consumerId);
    const numericCompanyId = Number(companyId);

    const updatedMessages = await sql`
      UPDATE consumer_company_messages
      SET read_at = CURRENT_TIMESTAMP
      WHERE consumer_id = ${numericConsumerId} 
        AND company_id = ${numericCompanyId}
        AND sender_type != ${user.type}
        AND read_at IS NULL
      RETURNING id, read_at
    `;

    await clearUnreadCount({
      consumerId: numericConsumerId,
      companyId: numericCompanyId,
      readerType: user.type,
    });
    
    return res.json({
      success: true,
      data: { count: updatedMessages.length }
    });
  } catch (error) {
    console.error('Error marking messages as read:', error);
    return res.status(500).json({ success: false, message: 'Failed to mark messages as read', error: error.message });
  }
};

// Get list of conversations for a user
export const getConversations = async (req, res) => {
  try {
    const user = req.user;
    
    if (user.type === 'consumer') {
      // Get all companies the consumer has access to and has messages with
      const dbConversations = await sql`
        SELECT DISTINCT
          m.company_id,
          c.name as company_name,
          c.description as company_description,
          (
            SELECT message_text 
            FROM consumer_company_messages 
            WHERE consumer_id = ${user.id} AND company_id = m.company_id
            ORDER BY sent_at DESC LIMIT 1
          ) as last_message,
          (
            SELECT sent_at 
            FROM consumer_company_messages 
            WHERE consumer_id = ${user.id} AND company_id = m.company_id
            ORDER BY sent_at DESC LIMIT 1
          ) as last_message_time,
          (
            SELECT COUNT(*) 
            FROM consumer_company_messages 
            WHERE consumer_id = ${user.id} AND company_id = m.company_id
              AND sender_type = 'company' AND read_at IS NULL
          ) as unread_count
        FROM consumer_company_messages m
        JOIN companies c ON m.company_id = c.id
        WHERE m.consumer_id = ${user.id}
        GROUP BY m.company_id, c.name, c.description
        ORDER BY last_message_time DESC NULLS LAST
      `;
      const redisCounts = await getConsumerUnreadSnapshot(user.id);
      const conversations = dbConversations.map((conv) => {
        const field = `company:${conv.company_id}`;
        const redisCount = redisCounts[field];
        const fallback = Number(conv.unread_count || 0);
        return {
          ...conv,
          unread_count:
            typeof redisCount === "number" ? redisCount : fallback,
        };
      });

      return res.json({ success: true, data: conversations });
    } else if (user.type === 'company') {
      // Get all consumers that have access to the company and have messages
      const dbConversations = await sql`
        SELECT DISTINCT
          m.consumer_id,
          cu.first_name || ' ' || cu.last_name as consumer_name,
          cu.email as consumer_email,
          (
            SELECT message_text 
            FROM consumer_company_messages 
            WHERE consumer_id = m.consumer_id AND company_id = ${user.company_id}
            ORDER BY sent_at DESC LIMIT 1
          ) as last_message,
          (
            SELECT sent_at 
            FROM consumer_company_messages 
            WHERE consumer_id = m.consumer_id AND company_id = ${user.company_id}
            ORDER BY sent_at DESC LIMIT 1
          ) as last_message_time,
          (
            SELECT COUNT(*) 
            FROM consumer_company_messages 
            WHERE consumer_id = m.consumer_id AND company_id = ${user.company_id}
              AND sender_type = 'consumer' AND read_at IS NULL
          ) as unread_count
        FROM consumer_company_messages m
        JOIN consumer_users cu ON m.consumer_id = cu.id
        WHERE m.company_id = ${user.company_id}
        GROUP BY m.consumer_id, cu.first_name, cu.last_name, cu.email
        ORDER BY last_message_time DESC NULLS LAST
      `;
      const redisCounts = await getCompanyUnreadSnapshot(user.company_id);
      const conversations = dbConversations.map((conv) => {
        const field = `consumer:${conv.consumer_id}`;
        const redisCount = redisCounts[field];
        const fallback = Number(conv.unread_count || 0);
        return {
          ...conv,
          unread_count:
            typeof redisCount === "number" ? redisCount : fallback,
        };
      });

      return res.json({ success: true, data: conversations });
    } else {
      return res.status(403).json({ success: false, message: 'Unauthorized user type' });
    }
  } catch (error) {
    console.error('Error fetching conversations:', error);
    return res.status(500).json({ success: false, message: 'Failed to fetch conversations', error: error.message });
  }
};

