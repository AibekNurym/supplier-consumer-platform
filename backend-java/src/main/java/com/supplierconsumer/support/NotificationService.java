package com.supplierconsumer.support;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplierconsumer.repo.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Creates notifications and reports unread counts.
 *
 * <p>Notifications are best-effort side effects throughout this codebase: every call site in the
 * original wraps the create in a try/catch that logs and continues, so a notification failure can
 * never roll back the order or issue that triggered it. That is why this runs in its own
 * transaction and why callers invoke it after commit.
 *
 * <p>This is also the only DTO in the API that is camelCase all the way through, because the
 * original maps the row by hand rather than returning it raw.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    public NotificationService(NotificationRepository notifications, ObjectMapper mapper,
                               ApplicationEventPublisher events) {
        this.notifications = notifications;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification createForConsumer(long consumerId, String title, String body,
                                          String actionType, Map<String, ?> actionPayload) {
        Notification created = create("consumer", consumerId, null, title, body, actionType,
                actionPayload);
        int unread = notifications.unreadCountForConsumer(consumerId);
        events.publishEvent(new NotificationCreated(created, unread));
        return created;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification createForCompany(long companyId, String title, String body,
                                         String actionType, Map<String, ?> actionPayload) {
        Notification created = create("company", null, companyId, title, body, actionType,
                actionPayload);
        int unread = notifications.unreadCountForCompany(companyId);
        events.publishEvent(new NotificationCreated(created, unread));
        return created;
    }

    private Notification create(String targetType, Long consumerId, Long companyId, String title,
                                String body, String actionType, Map<String, ?> actionPayload) {
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(actionPayload == null ? Map.of() : actionPayload);
        } catch (Exception e) {
            payloadJson = "{}";
        }
        return toDto(notifications.insert(targetType, consumerId, companyId, title, body,
                actionType, payloadJson));
    }

    /**
     * Runs a notification without letting it affect the caller. Used where the original wrapped
     * the call in a try/catch that only logged.
     */
    public void bestEffort(String description, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Failed to create {} notification: {}", description, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Notification toDto(Map<String, Object> row) {
        Object payload = row.get("action_payload");
        return new Notification(
                ((Number) row.get("id")).longValue(),
                (String) row.get("target_type"),
                row.get("target_consumer_id") == null
                        ? null : ((Number) row.get("target_consumer_id")).longValue(),
                row.get("target_company_id") == null
                        ? null : ((Number) row.get("target_company_id")).longValue(),
                (String) row.get("title"),
                (String) row.get("body"),
                (String) row.get("action_type"),
                payload instanceof Map ? (Map<String, Object>) payload : Map.of(),
                (String) row.get("created_at"),
                (String) row.get("read_at"));
    }

    @JsonPropertyOrder({"id", "targetType", "consumerId", "companyId", "title", "body",
            "actionType", "actionPayload", "createdAt", "readAt"})
    public record Notification(
            @JsonProperty("id") long id,
            @JsonProperty("targetType") String targetType,
            @JsonProperty("consumerId") Long consumerId,
            @JsonProperty("companyId") Long companyId,
            @JsonProperty("title") String title,
            @JsonProperty("body") String body,
            @JsonProperty("actionType") String actionType,
            @JsonProperty("actionPayload") Map<String, Object> actionPayload,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("readAt") String readAt) {
    }

    /** Published so the realtime layer can push the notification without this service knowing it. */
    public record NotificationCreated(Notification notification, int unreadCount) {
    }

    /** Published when a read changes a count, so the realtime layer can refresh badges. */
    public record NotificationUnreadChanged(String targetType, Long consumerId, Long companyId,
                                            int unreadCount) {
    }
}
