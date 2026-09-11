package com.supplierconsumer.chat;

import com.supplierconsumer.config.WebConfig;
import com.supplierconsumer.repo.ChatRepository;
import com.supplierconsumer.repo.ConsumerAccessRepository;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.support.ChatMessageService;
import com.supplierconsumer.support.ChatUnreadService;
import com.supplierconsumer.support.NotificationService;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.ApiResponse;
import com.supplierconsumer.wire.WireError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code /api/chat} -- the conversation between a buyer and a supplier.
 *
 * <p>This is the one area served by a single handler for both identities, which is why it takes a
 * {@link Principals.Chat} and branches on its type.
 *
 * <p>Errors here always include the raw exception text under an {@code error} key, unlike the rest
 * of the API where that is either suppressed or limited to the dev profile.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatRepository chat;
    private final ChatMessageService messages;
    private final ChatUnreadService unread;
    private final ConsumerAccessRepository access;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final WebConfig.UploadDirectories uploads;

    public ChatController(ChatRepository chat, ChatMessageService messages, ChatUnreadService unread,
                          ConsumerAccessRepository access, NotificationService notifications,
                          ApplicationEventPublisher events, WebConfig.UploadDirectories uploads) {
        this.chat = chat;
        this.messages = messages;
        this.unread = unread;
        this.access = access;
        this.notifications = notifications;
        this.events = events;
        this.uploads = uploads;
    }

    @GetMapping("/conversations")
    @WireError(message = "Failed to fetch conversations", detail = WireError.ErrorDetail.ALWAYS)
    public ApiResponse conversations(Principals.Chat user) {
        if (user.isConsumer()) {
            return ApiResponse.ok().data(overlayUnread(
                    chat.findConversationsForConsumer(user.id()), "consumer", user.id()));
        }
        if (user.isCompany() && user.companyId() != null) {
            return ApiResponse.ok().data(overlayUnread(
                    chat.findConversationsForCompany(user.companyId()), "company", user.companyId()));
        }
        throw ApiException.forbidden("Unauthorized user type");
    }

    @GetMapping("/messages/{consumerId}/{companyId}")
    @WireError(message = "Failed to fetch messages", detail = WireError.ErrorDetail.ALWAYS)
    public ApiResponse messages(@PathVariable String consumerId, @PathVariable String companyId,
                                Principals.Chat user) {
        long consumer = numericId(consumerId);
        long company = numericId(companyId);
        requireParticipant(user, consumer, company);

        return ApiResponse.ok().data(chat.findMessages(consumer, company));
    }

    /**
     * Posts a message. Returns 200 even though it creates a row, as the original does.
     *
     * <p>Accepts either JSON or multipart; with a file attached, the upload decides the message
     * type and supplies the attachment fields.
     */
    @PostMapping(path = "/messages/{consumerId}/{companyId}",
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE,
                    MediaType.ALL_VALUE})
    @WireError(message = "Failed to send message", detail = WireError.ErrorDetail.ALWAYS)
    @Transactional
    public ApiResponse send(@PathVariable String consumerId, @PathVariable String companyId,
                            @RequestParam(required = false) String message_text,
                            @RequestParam(required = false) String message_type,
                            @RequestParam(required = false) String attachment_url,
                            @RequestParam(required = false) String attachment_name,
                            @RequestParam(required = false) String reply_to_message_id,
                            @RequestParam(required = false) String product_id,
                            @RequestParam(required = false) MultipartFile attachment,
                            Principals.Chat user) throws IOException {

        long consumer = numericId(consumerId);
        long company = numericId(companyId);
        requireParticipant(user, consumer, company);

        String senderType = user.isConsumer() ? "consumer" : "company";
        Long productId = optionalId(product_id);
        Long replyTo = optionalId(reply_to_message_id);

        if (productId != null && !chat.productBelongsToCompany(productId, company)) {
            throw ApiException.badRequest("Product not found or does not belong to this company");
        }

        String type = message_type == null || message_type.isBlank() ? "text" : message_type;
        String url = attachment_url;
        String name = attachment_name;

        if (attachment != null && !attachment.isEmpty()) {
            StoredAttachment stored = store(attachment);
            url = stored.url();
            name = stored.originalName();
            type = stored.messageType();
        }

        // Both directions are gated: a supplier cannot message a buyer they have blocked or whose
        // access they revoked, just as the buyer cannot message them.
        if (!access.hasActiveAccess(consumer, company)) {
            throw ApiException.forbidden("You must have approved access to message this company");
        }
        if (access.isBlocked(consumer, company)) {
            throw ApiException.forbidden("Messaging is disabled because this consumer is blocked.");
        }

        Map<String, Object> message = messages.insert(consumer, company, senderType, user.id(),
                message_text, type, url, name, replyTo, productId);

        if (message == null) {
            throw new ApiException(500, "Failed to send message");
        }

        events.publishEvent(new ChatMessageSent(message, consumer, company, senderType,
                displayName(user), preview(message)));

        return ApiResponse.ok().data(message);
    }

    @PutMapping("/messages/{consumerId}/{companyId}/read")
    @WireError(message = "Failed to mark messages as read", detail = WireError.ErrorDetail.ALWAYS)
    @Transactional
    public ApiResponse markRead(@PathVariable String consumerId, @PathVariable String companyId,
                                Principals.Chat user) {
        long consumer = numericId(consumerId);
        long company = numericId(companyId);
        requireParticipant(user, consumer, company);

        int count = chat.markRead(consumer, company, user.type());
        unread.clear(consumer, company, user.type());

        return ApiResponse.ok().data(Map.of("count", count));
    }

    /**
     * A buyer may only act on their own conversations, a supplier only on their company's.
     */
    private void requireParticipant(Principals.Chat user, long consumerId, long companyId) {
        if (user.isConsumer()) {
            if (user.id() != consumerId) {
                throw ApiException.forbidden("Unauthorized");
            }
            return;
        }
        if (user.isCompany()) {
            if (user.companyId() == null || user.companyId() != companyId) {
                throw ApiException.forbidden("Unauthorized");
            }
            return;
        }
        throw ApiException.forbidden("Unauthorized");
    }

    /**
     * Replaces the SQL-derived unread count with the live counter where one exists.
     *
     * <p>The two can legitimately disagree: marking read over the socket clears the counter without
     * touching {@code read_at}, so the counter is the more current of the two.
     */
    private List<Map<String, Object>> overlayUnread(List<Map<String, Object>> conversations,
                                                    String type, long id) {
        Map<String, Integer> counts = "consumer".equals(type)
                ? unread.consumerSnapshot(id)
                : unread.companySnapshot(id);

        return conversations.stream().map(row -> {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            String field = "consumer".equals(type)
                    ? "company:" + row.get("company_id")
                    : "consumer:" + row.get("consumer_id");
            Integer live = counts.get(field);
            if (live != null) {
                copy.put("unread_count", live);
            }
            return copy;
        }).toList();
    }

    private StoredAttachment store(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String extension = originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.'))
                : "";
        String base = extension.isEmpty() ? originalName
                : originalName.substring(0, originalName.length() - extension.length());

        // Same shape the original generates: name-timestamp-random.ext
        String stored = base + "-" + System.currentTimeMillis() + "-"
                + ThreadLocalRandom.current().nextInt(1_000_000_000) + extension;

        Path target = uploads.chat().resolve(stored);
        Files.createDirectories(target.getParent());
        file.transferTo(target);

        return new StoredAttachment("/uploads/chat/" + stored, originalName,
                messageTypeFor(extension));
    }

    /** The message type is derived from the extension, exactly as the upload helper does. */
    private String messageTypeFor(String extension) {
        String ext = extension.toLowerCase(Locale.ROOT);
        if (List.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg").contains(ext)) {
            return "image";
        }
        if (List.of(".mp3", ".wav", ".ogg", ".mp4", ".m4a").contains(ext)) {
            return "audio";
        }
        return "file";
    }

    private String preview(Map<String, Object> message) {
        Object text = message.get("message_text");
        if (text != null && !text.toString().isBlank()) {
            return text.toString().trim();
        }
        return switch (String.valueOf(message.get("message_type"))) {
            case "audio" -> "Sent a voice message";
            case "image" -> "Sent an image";
            case "file" -> "Sent a file";
            default -> "Sent a message";
        };
    }

    private String displayName(Principals.Chat user) {
        if (user.firstName() != null && user.lastName() != null) {
            String name = (user.firstName() + " " + user.lastName()).trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        if (user.email() != null) {
            return user.email();
        }
        return user.isConsumer() ? "Consumer" : "Company representative";
    }

    private Long optionalId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid conversation identifiers");
        }
    }

    private record StoredAttachment(String url, String originalName, String messageType) {
    }

    /** Raised after commit so the realtime push and the notification cannot roll the message back. */
    public record ChatMessageSent(Map<String, Object> message, long consumerId, long companyId,
                                  String senderType, String senderName, String preview) {
    }
}
