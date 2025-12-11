import {
  createNotification,
  getCompanyNotifications,
  getCompanyUnreadCount,
  getConsumerNotifications,
  getConsumerUnreadCount,
  markCompanyNotificationsRead,
  markConsumerNotificationsRead,
} from "../services/notificationService.js";

export const getConsumerNotificationFeed = async (req, res) => {
  try {
    const consumerId = req.consumer.id;
    const limit = Number(req.query.limit) || 50;

    const [notifications, unreadCount] = await Promise.all([
      getConsumerNotifications(consumerId, { limit }),
      getConsumerUnreadCount(consumerId),
    ]);

    res.json({
      success: true,
      data: {
        notifications,
        unreadCount,
      },
    });
  } catch (error) {
    console.error("getConsumerNotificationFeed error:", error);
    res.status(500).json({
      success: false,
      message: "Failed to load notifications",
    });
  }
};

export const getCompanyNotificationFeed = async (req, res) => {
  try {
    const companyId = req.user.company_id;
    const limit = Number(req.query.limit) || 50;

    const [notifications, unreadCount] = await Promise.all([
      getCompanyNotifications(companyId, { limit }),
      getCompanyUnreadCount(companyId),
    ]);

    res.json({
      success: true,
      data: {
        notifications,
        unreadCount,
      },
    });
  } catch (error) {
    console.error("getCompanyNotificationFeed error:", error);
    res.status(500).json({
      success: false,
      message: "Failed to load notifications",
    });
  }
};

export const markConsumerNotificationFeedRead = async (req, res) => {
  try {
    const consumerId = req.consumer.id;
    const ids = Array.isArray(req.body.ids) ? req.body.ids.map(Number) : [];

    const unreadCount = await markConsumerNotificationsRead(consumerId, ids);

    res.json({
      success: true,
      data: { unreadCount },
    });
  } catch (error) {
    console.error("markConsumerNotificationFeedRead error:", error);
    res.status(500).json({
      success: false,
      message: "Failed to update notifications",
    });
  }
};

export const markCompanyNotificationFeedRead = async (req, res) => {
  try {
    const companyId = req.user.company_id;
    const ids = Array.isArray(req.body.ids) ? req.body.ids.map(Number) : [];

    const unreadCount = await markCompanyNotificationsRead(companyId, ids);

    res.json({
      success: true,
      data: { unreadCount },
    });
  } catch (error) {
    console.error("markCompanyNotificationFeedRead error:", error);
    res.status(500).json({
      success: false,
      message: "Failed to update notifications",
    });
  }
};

export const createNotificationForTesting = async (req, res) => {
  try {
    const {
      targetType,
      consumerId,
      companyId,
      title,
      body,
      actionType,
      actionPayload,
    } = req.body;

    const notification = await createNotification({
      targetType,
      consumerId,
      companyId,
      title,
      body,
      actionType,
      actionPayload,
    });

    res.status(201).json({
      success: true,
      data: notification,
    });
  } catch (error) {
    console.error("createNotificationForTesting error:", error);
    res.status(500).json({
      success: false,
      message: "Failed to create notification",
    });
  }
};



