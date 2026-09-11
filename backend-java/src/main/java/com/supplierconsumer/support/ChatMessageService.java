package com.supplierconsumer.support;

import com.supplierconsumer.repo.ChatRepository;
import com.supplierconsumer.wire.ApiException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Inserts a chat message and returns it hydrated.
 *
 * <p>Used both by the chat endpoint and by the order and issue flows, which post generated
 * summaries into the conversation so the supplier sees them in context.
 */
@Service
public class ChatMessageService {

    private final ChatRepository chat;

    public ChatMessageService(ChatRepository chat) {
        this.chat = chat;
    }

    /**
     * A message needs a sender and a conversation, and at least one of text, an attachment or a
     * product reference -- an attachment-only or product-only message is legitimate, which is why
     * empty text alone is not a rejection.
     */
    public Map<String, Object> insert(long consumerId, long companyId, String senderType,
                                      long senderId, String messageText, String messageType,
                                      String attachmentUrl, String attachmentName,
                                      Long replyToMessageId, Long productId) {

        String trimmed = messageText == null ? "" : messageText.trim();

        if (trimmed.isEmpty() && attachmentUrl == null && attachmentName == null && productId == null) {
            throw ApiException.badRequest("Message cannot be empty");
        }

        long id = chat.insertMessage(consumerId, companyId, senderType, senderId, trimmed,
                messageType == null ? "text" : messageType,
                attachmentUrl, attachmentName, replyToMessageId, productId);

        return chat.findHydrated(id).orElse(null);
    }
}
