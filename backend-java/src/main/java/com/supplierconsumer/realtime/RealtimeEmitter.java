package com.supplierconsumer.realtime;

import com.supplierconsumer.support.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Pushes payloads into Socket.IO rooms.
 *
 * <p>Every emit is swallowed on failure. Realtime here is a convenience on top of endpoints that
 * already returned successfully, so a socket problem must never turn into a failed request -- the
 * original wraps every emit the same way.
 *
 * <p>Resolved lazily so that the rest of the application works with the socket server switched off,
 * which is how the tests run.
 */
@Component
public class RealtimeEmitter {

    private static final Logger log = LoggerFactory.getLogger(RealtimeEmitter.class);

    private final ObjectProvider<SocketIoServerBean> socketServer;

    public RealtimeEmitter(ObjectProvider<SocketIoServerBean> socketServer) {
        this.socketServer = socketServer;
    }

    public void emit(List<String> rooms, String event, Object payload) {
        SocketIoServerBean bean = socketServer.getIfAvailable();
        if (bean == null || payload == null) {
            return;
        }
        try {
            for (String room : rooms) {
                if (room != null) {
                    bean.server().getRoomOperations(room).sendEvent(event, payload);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to emit {}: {}", event, e.getMessage());
        }
    }

    public void chatMessage(long consumerId, long companyId, Map<String, Object> message) {
        emit(Rooms.forChatMessage(consumerId, companyId), "chat:new_message", message);
    }

    public void orderUpdate(long consumerId, long companyId, Map<String, Object> order) {
        emit(Rooms.forParticipants(consumerId, companyId), "order:updated", order);
    }

    public void issueUpdate(long consumerId, long companyId, Map<String, Object> issue) {
        emit(Rooms.forParticipants(consumerId, companyId), "issue:updated", issue);
    }

    public void notification(NotificationService.Notification notification, int unreadCount) {
        emit(Rooms.forNotification(notification.targetType(), notification.consumerId(),
                        notification.companyId()),
                "notification:new",
                Map.of("notification", notification, "unreadCount", unreadCount));
    }

    public void notificationUnreadCount(String targetType, Long consumerId, Long companyId,
                                        int unreadCount) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("targetType", targetType);
        payload.put("consumerId", consumerId);
        payload.put("companyId", companyId);
        payload.put("unreadCount", unreadCount);
        emit(Rooms.forNotification(targetType, consumerId, companyId),
                "notification:unread_count", payload);
    }

    public void unreadUpdate(String type, long consumerId, long companyId, String field,
                             int unreadCount, int totalUnread) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", type);
        payload.put("consumerId", consumerId);
        payload.put("companyId", companyId);
        payload.put("field", field);
        payload.put("unreadCount", unreadCount);
        payload.put("totalUnread", totalUnread);
        emit(Rooms.forUnread(type, consumerId, companyId), "chat:unread_update", payload);
    }
}
