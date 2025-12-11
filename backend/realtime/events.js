import { getIO } from "./socket.js";

const safeEmit = (rooms, event, payload) => {
  try {
    const io = getIO();
    rooms.forEach((room) => {
      if (room) {
        io.to(room).emit(event, payload);
      }
    });
  } catch (error) {
    if (process.env.ENABLE_PROFILING === "true") {
      console.warn(`[socket] emit error (${event}):`, error.message);
    }
  }
};

export const emitChatMessage = (message) => {
  if (!message) return;
  const consumerId = message.consumer_id || message.consumerId;
  const companyId = message.company_id || message.companyId;

  const rooms = [
    consumerId ? `consumer:${consumerId}` : null,
    companyId ? `company:${companyId}` : null,
    consumerId && companyId ? `chat:${consumerId}:${companyId}` : null,
  ];

  safeEmit(rooms, "chat:new_message", message);
};

export const emitIssueUpdate = (issue) => {
  if (!issue) return;
  const consumerId = issue.consumer_id || issue.consumerId;
  const companyId = issue.company_id || issue.companyId;

  const rooms = [
    consumerId ? `consumer:${consumerId}` : null,
    companyId ? `company:${companyId}` : null,
  ];

  safeEmit(rooms, "issue:updated", issue);
};

export const emitOrderUpdate = (order) => {
  if (!order) return;
  const consumerId = order.consumer_id || order.consumerId;
  const companyId = order.company_id || order.companyId;

  const rooms = [
    consumerId ? `consumer:${consumerId}` : null,
    companyId ? `company:${companyId}` : null,
  ];

  safeEmit(rooms, "order:updated", order);
};

export const emitUnreadUpdate = (rooms, payload) => {
  const targetRooms = Array.isArray(rooms) ? rooms : [rooms];
  safeEmit(
    targetRooms.filter(Boolean),
    "chat:unread_update",
    payload
  );
};

const notificationRooms = ({ targetType, consumerId, companyId }) => {
  if (targetType === "consumer" && consumerId) {
    return [`user:consumer:${consumerId}`, `consumer:${consumerId}`];
  }

  if (targetType === "company" && companyId) {
    return [`user:company:${companyId}`, `company:${companyId}`];
  }

  return [];
};

export const emitNotification = (notification, unreadCount) => {
  const rooms = notificationRooms({
    targetType: notification.targetType,
    consumerId: notification.consumerId,
    companyId: notification.companyId,
  });

  if (rooms.length === 0) return;

  safeEmit(rooms, "notification:new", {
    notification,
    unreadCount,
  });
};

export const emitNotificationUnreadCount = ({ targetType, consumerId, companyId, unreadCount }) => {
  const rooms = notificationRooms({ targetType, consumerId, companyId });
  if (rooms.length === 0) return;
  safeEmit(rooms, "notification:unread_count", {
    targetType,
    consumerId,
    companyId,
    unreadCount,
  });
};

