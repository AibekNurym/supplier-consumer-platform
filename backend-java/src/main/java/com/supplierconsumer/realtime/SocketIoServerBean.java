package com.supplierconsumer.realtime;

import com.corundumstudio.socketio.AuthorizationListener;
import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.supplierconsumer.config.AppProperties;
import com.supplierconsumer.repo.AuthRepository;
import com.supplierconsumer.repo.NotificationRepository;
import com.supplierconsumer.security.JwtService;
import com.supplierconsumer.support.ChatUnreadService;
import com.supplierconsumer.support.NotificationService;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Socket.IO endpoint, speaking protocol v4 so existing socket.io clients connect unchanged.
 *
 * <p>One deliberate departure from the original: the handshake is authenticated and rooms are
 * derived from the token. The original has no handshake check at all -- a client emits
 * {@code register} with whatever ids it likes, plus an arbitrary {@code rooms} array, and the
 * server joins it to all of them. Anyone could therefore read any company's messages and
 * notifications simply by naming the room. The {@code register} event is still accepted for
 * protocol compatibility, but the ids it carries are ignored in favour of the token's claims.
 *
 * <p>Runs its own Netty listener on a separate port, as netty-socketio does; the HTTP API is
 * unaffected.
 */
@Component
@ConditionalOnProperty(name = "app.socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIoServerBean {

    private static final Logger log = LoggerFactory.getLogger(SocketIoServerBean.class);

    private static final String CONSUMER_ID = "scpConsumerId";
    private static final String USER_ID = "scpUserId";
    private static final String COMPANY_ID = "scpCompanyId";

    private final SocketIOServer server;
    private final ChatUnreadService unread;
    private final NotificationRepository notifications;
    private final NotificationService notificationService;

    public SocketIoServerBean(AppProperties props, JwtService jwt, AuthRepository auth,
                              ChatUnreadService unread, NotificationRepository notifications,
                              NotificationService notificationService) {
        this.unread = unread;
        this.notifications = notifications;
        this.notificationService = notificationService;

        Configuration config = new Configuration();
        config.setHostname(props.socketio().host());
        config.setPort(props.socketio().port());
        config.getSocketConfig().setReuseAddress(true);
        config.setOrigin("*");
        config.setAuthorizationListener(handshakeAuthorizer(jwt, auth));

        this.server = new SocketIOServer(config);
        registerHandlers();
        this.server.start();
        log.info("Socket.IO listening on {}:{}", props.socketio().host(), props.socketio().port());
    }

    /**
     * Validates the token presented at the handshake, accepting either identity.
     *
     * <p>Clients may send it as an {@code auth.token} field, a {@code token} query parameter, or an
     * Authorization header, since socket.io clients differ in which they use.
     */
    private AuthorizationListener handshakeAuthorizer(JwtService jwt, AuthRepository auth) {
        return data -> {
            String token = data.getSingleUrlParam("token");
            if (token == null) {
                String header = data.getHttpHeaders() == null
                        ? null : data.getHttpHeaders().get("Authorization");
                if (header != null && header.startsWith("Bearer ")) {
                    token = header.substring(7);
                }
            }
            if (token == null) {
                log.debug("Socket.IO handshake rejected: no token");
                return AuthorizationResult.FAILED_AUTHORIZATION;
            }

            try {
                Claims claims = jwt.verifyAnyAccess(token);

                // Whatever is put here lands in the client's store, so the identity is resolved
                // once at connect time and read back per event without touching the database.
                Map<String, Object> store = new LinkedHashMap<>();

                Number consumerId = claims.get("consumerId", Number.class);
                Number userId = claims.get("userId", Number.class);
                if (consumerId != null) {
                    store.put(CONSUMER_ID, consumerId.longValue());
                } else if (userId != null) {
                    // Only an active account gets through, so a disabled employee's socket is
                    // refused rather than merely receiving nothing.
                    var user = auth.findActiveCompanyUser(userId.longValue());
                    if (user.isEmpty() || user.get().companyId() == null) {
                        return AuthorizationResult.FAILED_AUTHORIZATION;
                    }
                    store.put(USER_ID, userId.longValue());
                    store.put(COMPANY_ID, user.get().companyId());
                } else {
                    return AuthorizationResult.FAILED_AUTHORIZATION;
                }
                return new AuthorizationResult(true, store);
            } catch (Exception e) {
                log.debug("Socket.IO handshake rejected: {}", e.getMessage());
                return AuthorizationResult.FAILED_AUTHORIZATION;
            }
        };
    }

    private void registerHandlers() {
        server.addConnectListener(client -> {
            Identity identity = identityOf(client);
            if (identity == null) {
                client.disconnect();
                return;
            }
            joinOwnRooms(client, identity);
            log.debug("Socket.IO client connected as {}", identity);
        });

        // Kept for protocol compatibility. The ids the client sends are ignored: membership comes
        // from the token, so a client cannot name its way into another company's room.
        server.addEventListener("register", Object.class, (client, data, ack) -> {
            Identity identity = identityOf(client);
            if (identity != null) {
                joinOwnRooms(client, identity);
            }
        });

        server.addEventListener("joinChat", ChatRoomRequest.class, (client, data, ack) -> {
            Identity identity = identityOf(client);
            if (identity != null && data != null && identity.mayJoin(data)) {
                client.joinRoom(Rooms.conversation(data.consumerId(), data.companyId()));
            }
        });

        server.addEventListener("leaveChat", ChatRoomRequest.class, (client, data, ack) -> {
            if (data != null) {
                client.leaveRoom(Rooms.conversation(data.consumerId(), data.companyId()));
            }
        });

        server.addEventListener("chat:mark_read", ChatRoomRequest.class, (client, data, ack) -> {
            Identity identity = identityOf(client);
            if (identity == null || data == null || !identity.mayJoin(data)) {
                return;
            }
            // The reader is taken from the token rather than from the payload, unlike the original
            // which trusts a role the client declared at registration.
            unread.clear(data.consumerId(), data.companyId(), identity.type());
        });

        server.addEventListener("chat:request_unread_snapshot", Object.class, (client, data, ack) -> {
            Identity identity = identityOf(client);
            if (identity == null) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", identity.type());
            if (identity.isConsumer()) {
                payload.put("consumerId", identity.id());
                payload.put("counts", unread.consumerSnapshot(identity.id()));
            } else {
                payload.put("companyId", identity.companyId());
                payload.put("counts", unread.companySnapshot(identity.companyId()));
            }
            client.sendEvent("chat:unread_snapshot", payload);
        });

        server.addEventListener("notification:request_snapshot", SnapshotRequest.class,
                (client, data, ack) -> {
                    Identity identity = identityOf(client);
                    if (identity == null) {
                        return;
                    }
                    int limit = data == null || data.limit() == null ? 50 : data.limit();

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("type", identity.type());
                    if (identity.isConsumer()) {
                        payload.put("consumerId", identity.id());
                        payload.put("notifications", notifications
                                .findForConsumer(identity.id(), limit).stream()
                                .map(notificationService::toDto).toList());
                        payload.put("unreadCount",
                                notifications.unreadCountForConsumer(identity.id()));
                    } else {
                        payload.put("companyId", identity.companyId());
                        payload.put("notifications", notifications
                                .findForCompany(identity.companyId(), limit).stream()
                                .map(notificationService::toDto).toList());
                        payload.put("unreadCount",
                                notifications.unreadCountForCompany(identity.companyId()));
                    }
                    client.sendEvent("notification:snapshot", payload);
                });

        server.addEventListener("notification:mark_read", MarkReadRequest.class, (client, data, ack) -> {
            Identity identity = identityOf(client);
            if (identity == null || data == null || data.notificationIds() == null
                    || data.notificationIds().isEmpty()) {
                return;
            }
            if (identity.isConsumer()) {
                notifications.markReadForConsumer(identity.id(), data.notificationIds());
            } else {
                notifications.markReadForCompany(identity.companyId(), data.notificationIds());
            }
        });

        server.addDisconnectListener(client ->
                log.debug("Socket.IO client disconnected: {}", client.getSessionId()));
    }

    /** Joins the rooms this identity is entitled to, and only those. */
    private void joinOwnRooms(SocketIOClient client, Identity identity) {
        if (identity.isConsumer()) {
            client.joinRoom(Rooms.consumer(identity.id()));
            client.joinRoom(Rooms.userConsumer(identity.id()));
        } else if (identity.companyId() != null) {
            client.joinRoom(Rooms.company(identity.companyId()));
            client.joinRoom(Rooms.userCompany(identity.companyId()));
        }
    }

    private Identity identityOf(SocketIOClient client) {
        Long consumerId = client.get(CONSUMER_ID);
        if (consumerId != null) {
            return new Identity("consumer", consumerId, null);
        }
        Long userId = client.get(USER_ID);
        if (userId != null) {
            return new Identity("company", userId, client.get(COMPANY_ID));
        }
        return null;
    }

    public SocketIOServer server() {
        return server;
    }

    @PreDestroy
    public void stop() {
        server.stop();
        log.info("Socket.IO stopped");
    }

    /** Who a connection belongs to, resolved from the token rather than from anything it sends. */
    private record Identity(String type, long id, Long companyId) {

        boolean isConsumer() {
            return "consumer".equals(type);
        }

        /** A client may only address a conversation it is actually part of. */
        boolean mayJoin(ChatRoomRequest request) {
            if (isConsumer()) {
                return request.consumerId() == id;
            }
            return companyId != null && companyId.longValue() == request.companyId();
        }
    }

    public record ChatRoomRequest(long consumerId, long companyId) {
    }

    public record SnapshotRequest(Integer limit) {
    }

    public record MarkReadRequest(List<Long> notificationIds) {
    }
}
