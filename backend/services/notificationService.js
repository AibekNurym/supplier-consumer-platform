import { sql } from "../config/db.js";
import { emitNotification, emitNotificationUnreadCount } from "../realtime/events.js";

const formatNotificationRow = (row) => ({
  id: row.id,
  targetType: row.target_type,
  consumerId: row.target_consumer_id,
  companyId: row.target_company_id,
  title: row.title,
  body: row.body,
  actionType: row.action_type,
  actionPayload: row.action_payload || {},
  createdAt: row.created_at,
  readAt: row.read_at,
});

const getUnreadCount = async ({ targetType, consumerId, companyId }) => {
  if (targetType === "consumer") {
    const [row] = await sql`
      SELECT COUNT(*)::INT AS count
      FROM notifications
      WHERE target_type = 'consumer'
        AND target_consumer_id = ${consumerId}
        AND read_at IS NULL
    `;
    return row?.count || 0;
  }

  if (targetType === "company") {
    const [row] = await sql`
      SELECT COUNT(*)::INT AS count
      FROM notifications
      WHERE target_type = 'company'
        AND target_company_id = ${companyId}
        AND read_at IS NULL
    `;
    return row?.count || 0;
  }

  return 0;
};

export const createNotification = async ({
  targetType,
  consumerId = null,
  companyId = null,
  title,
  body,
  actionType,
  actionPayload = {},
}) => {
  if (!targetType || !title || !body || !actionType) {
    throw new Error("Missing fields to create notification");
  }

  const [inserted] = await sql`
    INSERT INTO notifications (
      target_type,
      target_consumer_id,
      target_company_id,
      title,
      body,
      action_type,
      action_payload
    )
    VALUES (
      ${targetType},
      ${consumerId},
      ${companyId},
      ${title},
      ${body},
      ${actionType},
      ${actionPayload}
    )
    RETURNING *
  `;

  const notification = formatNotificationRow(inserted);
  const unreadCount = await getUnreadCount({ targetType, consumerId, companyId });

  emitNotification(notification, unreadCount);

  return notification;
};

export const getConsumerNotifications = async (consumerId, { limit = 50 } = {}) => {
  const rows = await sql`
    SELECT *
    FROM notifications
    WHERE target_type = 'consumer'
      AND target_consumer_id = ${consumerId}
    ORDER BY created_at DESC
    LIMIT ${limit}
  `;
  return rows.map(formatNotificationRow);
};

export const getCompanyNotifications = async (companyId, { limit = 50 } = {}) => {
  const rows = await sql`
    SELECT *
    FROM notifications
    WHERE target_type = 'company'
      AND target_company_id = ${companyId}
    ORDER BY created_at DESC
    LIMIT ${limit}
  `;
  return rows.map(formatNotificationRow);
};

const markNotificationsRead = async ({ targetType, consumerId, companyId, notificationIds }) => {
  if (!Array.isArray(notificationIds) || notificationIds.length === 0) {
    return 0;
  }

  const now = new Date();

  if (targetType === "consumer") {
    await sql`
      UPDATE notifications
      SET read_at = ${now}
      WHERE target_type = 'consumer'
        AND target_consumer_id = ${consumerId}
        AND id = ANY(${notificationIds}::INT[])
    `;
  } else if (targetType === "company") {
    await sql`
      UPDATE notifications
      SET read_at = ${now}
      WHERE target_type = 'company'
        AND target_company_id = ${companyId}
        AND id = ANY(${notificationIds}::INT[])
    `;
  }

  return getUnreadCount({ targetType, consumerId, companyId });
};

export const markConsumerNotificationsRead = async (consumerId, notificationIds) => {
  const unreadCount = await markNotificationsRead({
    targetType: "consumer",
    consumerId,
    notificationIds,
  });

  emitNotificationUnreadCount({
    targetType: "consumer",
    consumerId,
    unreadCount,
  });

  return unreadCount;
};

export const markCompanyNotificationsRead = async (companyId, notificationIds) => {
  const unreadCount = await markNotificationsRead({
    targetType: "company",
    companyId,
    notificationIds,
  });

  emitNotificationUnreadCount({
    targetType: "company",
    companyId,
    unreadCount,
  });

  return unreadCount;
};

export const getConsumerUnreadCount = async (consumerId) =>
  getUnreadCount({ targetType: "consumer", consumerId });

export const getCompanyUnreadCount = async (companyId) =>
  getUnreadCount({ targetType: "company", companyId });



