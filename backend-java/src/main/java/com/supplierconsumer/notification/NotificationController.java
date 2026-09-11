package com.supplierconsumer.notification;

import com.supplierconsumer.repo.NotificationRepository;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.support.NotificationService;
import com.supplierconsumer.wire.ApiResponse;
import com.supplierconsumer.wire.WireError;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/notifications} -- the notification feed for both identities.
 *
 * <p>Company notifications target the company, not an individual, so there is no per-employee read
 * state: once anyone in the company reads one, it is read for all of them. Preserved, since
 * changing it changes what users see.
 *
 * <p>These payloads are the only fully camelCase DTOs in the API, because the original maps the
 * row by hand rather than returning it raw. The unread count is a JSON number here while most
 * other counts in this API are strings, because this query casts it.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notifications;
    private final NotificationService service;
    private final ApplicationEventPublisher events;

    public NotificationController(NotificationRepository notifications, NotificationService service,
                                  ApplicationEventPublisher events) {
        this.notifications = notifications;
        this.service = service;
        this.events = events;
    }

    @GetMapping("/consumer")
    @WireError(message = "Failed to load notifications")
    public ApiResponse consumerFeed(@RequestParam(required = false) String limit,
                                    Principals.Consumer consumer) {
        int max = parseLimit(limit);
        return ApiResponse.ok().data(feed(
                notifications.findForConsumer(consumer.id(), max),
                notifications.unreadCountForConsumer(consumer.id())));
    }

    @PostMapping("/consumer/mark-read")
    @WireError(message = "Failed to update notifications")
    @Transactional
    public ApiResponse consumerMarkRead(@RequestBody(required = false) MarkReadRequest request,
                                        Principals.Consumer consumer) {
        List<Long> ids = request == null ? List.of() : request.idsOrEmpty();

        // An empty list is a no-op. The original short-circuits to zero and then broadcasts a
        // "zero unread" update, which wrongly clears the badge of anyone listening.
        if (!ids.isEmpty()) {
            notifications.markReadForConsumer(consumer.id(), ids);
            int unread = notifications.unreadCountForConsumer(consumer.id());
            events.publishEvent(new NotificationService.NotificationUnreadChanged(
                    "consumer", consumer.id(), null, unread));
            return ApiResponse.ok().data(Map.of("unreadCount", unread));
        }

        return ApiResponse.ok().data(Map.of(
                "unreadCount", notifications.unreadCountForConsumer(consumer.id())));
    }

    @GetMapping("/company")
    @WireError(message = "Failed to load notifications")
    public ApiResponse companyFeed(@RequestParam(required = false) String limit,
                                   Principals.Company user) {
        long companyId = user.requireCompanyId("User must be associated with a company");
        int max = parseLimit(limit);
        return ApiResponse.ok().data(feed(
                notifications.findForCompany(companyId, max),
                notifications.unreadCountForCompany(companyId)));
    }

    @PostMapping("/company/mark-read")
    @WireError(message = "Failed to update notifications")
    @Transactional
    public ApiResponse companyMarkRead(@RequestBody(required = false) MarkReadRequest request,
                                       Principals.Company user) {
        long companyId = user.requireCompanyId("User must be associated with a company");
        List<Long> ids = request == null ? List.of() : request.idsOrEmpty();

        if (!ids.isEmpty()) {
            notifications.markReadForCompany(companyId, ids);
            int unread = notifications.unreadCountForCompany(companyId);
            events.publishEvent(new NotificationService.NotificationUnreadChanged(
                    "company", null, companyId, unread));
            return ApiResponse.ok().data(Map.of("unreadCount", unread));
        }

        return ApiResponse.ok().data(Map.of(
                "unreadCount", notifications.unreadCountForCompany(companyId)));
    }

    /** A hand-testing hook the original exposes; kept behind company authentication as it was. */
    @PostMapping("/debug/create")
    @WireError(message = "Failed to create notification")
    public ResponseEntity<ApiResponse> debugCreate(Principals.Company user) {
        long companyId = user.requireCompanyId("User must be associated with a company");
        NotificationService.Notification created = service.createForCompany(companyId,
                "Test notification", "This is a test notification.", "debug:test", Map.of());
        return ResponseEntity.status(201).body(ApiResponse.ok().data(created));
    }

    private Map<String, Object> feed(List<Map<String, Object>> rows, int unreadCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notifications", rows.stream().map(service::toDto).toList());
        data.put("unreadCount", unreadCount);
        return data;
    }

    private int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return 50;
        }
        try {
            int value = Integer.parseInt(limit);
            return value > 0 ? value : 50;
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    public record MarkReadRequest(List<Long> ids) {
        List<Long> idsOrEmpty() {
            return ids == null ? List.of() : ids;
        }
    }
}
