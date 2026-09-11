package com.supplierconsumer.realtime;

import com.supplierconsumer.chat.ChatController;
import com.supplierconsumer.consumercart.CartService;
import com.supplierconsumer.issue.IssueService;
import com.supplierconsumer.order.OrderService;
import com.supplierconsumer.support.ChatMessageService;
import com.supplierconsumer.support.ChatUnreadService;
import com.supplierconsumer.support.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Everything that happens <em>after</em> a request's work is safely committed: the socket pushes,
 * the unread counters and the notifications.
 *
 * <p>These run at {@code AFTER_COMMIT}, which is what makes them genuinely best-effort. Spring
 * swallows exceptions thrown from an after-commit synchronisation, so a failing notification
 * physically cannot turn a successful order into a 500. That reproduces the original's behaviour --
 * where each of these sits in a try/catch after the response has already been written -- but
 * enforces it structurally rather than by remembering to catch.
 *
 * <p>One listener per event, rather than one per side effect, so the ordering the original relies
 * on stays visible in one place.
 */
@Component
public class SideEffectListeners {

    private static final Logger log = LoggerFactory.getLogger(SideEffectListeners.class);

    private final RealtimeEmitter realtime;
    private final ChatUnreadService unread;
    private final NotificationService notifications;
    private final ChatMessageService chatMessages;

    public SideEffectListeners(RealtimeEmitter realtime, ChatUnreadService unread,
                               NotificationService notifications, ChatMessageService chatMessages) {
        this.realtime = realtime;
        this.unread = unread;
        this.notifications = notifications;
        this.chatMessages = chatMessages;
    }

    @TransactionalEventListener
    public void onChatMessage(ChatController.ChatMessageSent event) {
        realtime.chatMessage(event.consumerId(), event.companyId(), event.message());
        unread.increment(event.consumerId(), event.companyId(), event.senderType());

        notifications.bestEffort("chat message", () -> {
            String title = "New message from " + event.senderName();
            if ("consumer".equals(event.senderType())) {
                notifications.createForCompany(event.companyId(), title, event.preview(),
                        "chat:message", Map.of("consumerId", event.consumerId(),
                                "companyId", event.companyId(),
                                "messageId", event.message().get("id")));
            } else {
                notifications.createForConsumer(event.consumerId(), title, event.preview(),
                        "chat:message", Map.of("consumerId", event.consumerId(),
                                "companyId", event.companyId(),
                                "messageId", event.message().get("id")));
            }
        });
    }

    /**
     * A placed order announces itself in the conversation as well as by notification, so the
     * supplier sees it wherever they happen to be looking.
     */
    @TransactionalEventListener
    public void onOrderPlaced(CartService.OrderPlaced event) {
        try {
            Map<String, Object> message = chatMessages.insert(event.consumerId(), event.companyId(),
                    "consumer", event.consumerId(), event.chatSummary(), "text",
                    null, null, null, null);
            if (message != null) {
                realtime.chatMessage(event.consumerId(), event.companyId(), message);
                unread.increment(event.consumerId(), event.companyId(), "consumer");
            }
        } catch (Exception e) {
            // Explicitly non-fatal: the order exists regardless of whether the summary posts.
            log.warn("Failed to post order summary to chat: {}", e.getMessage());
        }

        notifications.bestEffort("new order", () -> notifications.createForCompany(
                event.companyId(), "New order #" + event.orderId(),
                event.customerName() + " placed an order totaling ₸" + event.totalFormatted(),
                "order:new", Map.of("orderId", event.orderId(),
                        "consumerId", event.consumerId(), "companyId", event.companyId())));
    }

    @TransactionalEventListener
    public void onOrderTransitioned(OrderService.OrderTransitioned event) {
        realtime.orderUpdate(event.consumerId(), event.companyId(), event.row());

        notifications.bestEffort("order " + event.transition(), () -> {
            String title = "Order #" + event.orderId() + " " + event.transition();
            String body = switch (event.transition()) {
                case "accepted" -> "Your order has been accepted and is being processed.";
                case "rejected" -> "Your order has been rejected.";
                default -> "Your order has been completed.";
            };
            Map<String, Object> payload = Map.of("orderId", event.orderId(),
                    "companyId", event.companyId());

            // Completion is the buyer telling the supplier; the other two are the reverse.
            if ("completed".equals(event.transition())) {
                notifications.createForCompany(event.companyId(), title,
                        "The customer marked this order as completed.",
                        "order:completed", payload);
            } else {
                notifications.createForConsumer(event.consumerId(), title, body,
                        "order:" + event.transition(), payload);
            }
        });
    }

    @TransactionalEventListener
    public void onIssueReported(IssueService.IssueReported event) {
        realtime.chatMessage(event.consumerId(), event.companyId(), event.chatMessage());
        unread.increment(event.consumerId(), event.companyId(), "consumer");
        realtime.issueUpdate(event.consumerId(), event.companyId(), event.issue());

        notifications.bestEffort("new issue", () -> notifications.createForCompany(
                event.companyId(), "New issue #" + event.issue().get("id"),
                event.consumerName() + " reported an issue for order #" + event.orderId(),
                "issue:new", Map.of("issueId", event.issue().get("id"),
                        "orderId", event.orderId(), "companyId", event.companyId())));
    }

    @TransactionalEventListener
    public void onIssueUpdated(IssueService.IssueUpdated event) {
        realtime.issueUpdate(event.consumerId(), event.companyId(), event.issue());
    }

    @TransactionalEventListener
    public void onIssueResolved(IssueService.IssueResolved event) {
        if (event.chatMessage() != null) {
            realtime.chatMessage(event.consumerId(), event.companyId(), event.chatMessage());
            unread.increment(event.consumerId(), event.companyId(), "company");
        }
        realtime.issueUpdate(event.consumerId(), event.companyId(), event.issue());

        // The original addresses this notification with a value read off the wrong object, so it
        // is stored with no recipient and the buyer never sees it. Addressed correctly here.
        notifications.bestEffort("issue resolved", () -> notifications.createForConsumer(
                event.consumerId(), "Issue resolved",
                "Your reported issue has been resolved.", "issue:resolved",
                Map.of("issueId", event.issue().get("id"), "orderId", event.orderId(),
                        "companyId", event.companyId())));
    }

    /** Fires outside a transaction, so it is delivered directly rather than after commit. */
    @org.springframework.context.event.EventListener
    public void onNotificationCreated(NotificationService.NotificationCreated event) {
        realtime.notification(event.notification(), event.unreadCount());
    }

    @org.springframework.context.event.EventListener
    public void onNotificationUnreadChanged(NotificationService.NotificationUnreadChanged event) {
        realtime.notificationUnreadCount(event.targetType(), event.consumerId(), event.companyId(),
                event.unreadCount());
    }

    @org.springframework.context.event.EventListener
    public void onUnreadChanged(ChatUnreadService.UnreadChanged event) {
        realtime.unreadUpdate(event.type(), event.consumerId(), event.companyId(), event.field(),
                event.unreadCount(), event.totalUnread());
    }
}
