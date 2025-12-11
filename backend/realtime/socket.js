import { Server } from "socket.io";
import {
  clearUnreadCount,
  getCompanyUnreadSnapshot,
  getConsumerUnreadSnapshot,
} from "../services/chatUnreadService.js";
import {
  getCompanyNotifications,
  getCompanyUnreadCount,
  getConsumerNotifications,
  getConsumerUnreadCount,
  markCompanyNotificationsRead,
  markConsumerNotificationsRead,
} from "../services/notificationService.js";

let io;

export const initSocket = (server) => {
  io = new Server(server, {
    cors: {
      origin: "*",
      methods: ["GET", "POST"],
    },
  });

  io.on("connection", (socket) => {
    const describe = () => ({
      id: socket.id,
      rooms: Array.from(socket.rooms),
    });

    socket.on(
      "register",
      ({ consumerId, companyId, role, rooms, userId } = {}) => {
      try {
          socket.data.consumerId = consumerId ?? null;
          socket.data.companyId = companyId ?? null;
          socket.data.role = role ?? null;
          socket.data.userId = userId ?? null;

        if (Array.isArray(rooms)) {
          rooms.forEach((room) => typeof room === "string" && socket.join(room));
        }
        if (consumerId) {
          socket.join(`consumer:${consumerId}`);
            socket.join(`user:consumer:${consumerId}`);
        }
        if (companyId) {
          socket.join(`company:${companyId}`);
            socket.join(`user:company:${companyId}`);
        }
        if (consumerId && companyId) {
          socket.join(`chat:${consumerId}:${companyId}`);
        }
          if (userId) {
            socket.join(`user:${userId}`);
          }
        console.log(`[socket] register ${socket.id}`, {
          role,
          consumerId,
          companyId,
            userId,
          rooms: Array.from(socket.rooms),
        });
      } catch (err) {
        console.error("[socket] register error", err.message);
      }
    });

    socket.on("joinChat", ({ consumerId, companyId } = {}) => {
      if (consumerId && companyId) {
        socket.join(`chat:${consumerId}:${companyId}`);
      }
    });

    socket.on("leaveChat", ({ consumerId, companyId } = {}) => {
      if (consumerId && companyId) {
        socket.leave(`chat:${consumerId}:${companyId}`);
      }
    });

    socket.on("chat:mark_read", async ({ consumerId, companyId } = {}) => {
      const readerType = socket.data.role;
      if (!readerType || !consumerId || !companyId) {
        return;
      }
      await clearUnreadCount({ consumerId, companyId, readerType });
    });

    socket.on("chat:request_unread_snapshot", async () => {
      try {
        if (socket.data.consumerId) {
          const counts = await getConsumerUnreadSnapshot(socket.data.consumerId);
          socket.emit("chat:unread_snapshot", {
            type: "consumer",
            consumerId: socket.data.consumerId,
            counts,
          });
        }
        if (socket.data.companyId) {
          const counts = await getCompanyUnreadSnapshot(socket.data.companyId);
          socket.emit("chat:unread_snapshot", {
            type: "company",
            companyId: socket.data.companyId,
            counts,
          });
        }
      } catch (error) {
        console.error("[socket] unread snapshot error:", error.message);
      }
    });

    socket.on("notification:request_snapshot", async ({ limit } = {}) => {
      try {
        if (socket.data.consumerId) {
          const [notifications, unreadCount] = await Promise.all([
            getConsumerNotifications(socket.data.consumerId, { limit }),
            getConsumerUnreadCount(socket.data.consumerId),
          ]);
          socket.emit("notification:snapshot", {
            type: "consumer",
            consumerId: socket.data.consumerId,
            notifications,
            unreadCount,
          });
        }

        if (socket.data.companyId) {
          const [notifications, unreadCount] = await Promise.all([
            getCompanyNotifications(socket.data.companyId, { limit }),
            getCompanyUnreadCount(socket.data.companyId),
          ]);
          socket.emit("notification:snapshot", {
            type: "company",
            companyId: socket.data.companyId,
            notifications,
            unreadCount,
          });
        }
      } catch (error) {
        console.error("[socket] notification snapshot error:", error.message);
      }
    });

    socket.on("notification:mark_read", async ({ notificationIds } = {}) => {
      try {
        if (!Array.isArray(notificationIds) || notificationIds.length === 0) {
          return;
        }

        if (socket.data.consumerId) {
          await markConsumerNotificationsRead(socket.data.consumerId, notificationIds);
        } else if (socket.data.companyId) {
          await markCompanyNotificationsRead(socket.data.companyId, notificationIds);
        }
      } catch (error) {
        console.error("[socket] notification mark_read error:", error.message);
      }
    });

    socket.on("disconnect", (reason) => {
      console.log(`[socket] disconnect ${socket.id} ${reason}`, describe());
    });
  });

  return io;
};

export const getIO = () => {
  if (!io) {
    throw new Error("Socket.io not initialised");
  }
  return io;
};

