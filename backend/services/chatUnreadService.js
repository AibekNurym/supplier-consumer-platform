import { getRedisClient } from "../config/redisClient.js";
import { emitUnreadUpdate } from "../realtime/events.js";

const consumerHashKey = (consumerId) => `chat:unread:consumer:${consumerId}`;
const companyHashKey = (companyId) => `chat:unread:company:${companyId}`;

const consumerField = (companyId) => `company:${companyId}`;
const companyField = (consumerId) => `consumer:${consumerId}`;

const consumerRooms = (consumerId) => [
  `user:consumer:${consumerId}`,
  `consumer:${consumerId}`,
];

const companyRooms = (companyId) => [
  `user:company:${companyId}`,
  `company:${companyId}`,
];

const sumValues = (record) => {
  if (!record) return 0;
  return Object.values(record).reduce(
    (acc, value) => acc + Number(value || 0),
    0
  );
};

const parseCounts = (record = {}) =>
  Object.fromEntries(
    Object.entries(record).map(([key, value]) => [key, Number(value || 0)])
  );

// In-memory fallback store when Redis is unavailable.
const fallbackConsumerStore = new Map(); // key -> Map(field -> count)
const fallbackCompanyStore = new Map();

const getFallbackRecord = (store, key) => {
  if (!store.has(key)) {
    store.set(key, new Map());
  }
  return store.get(key);
};

const fallbackToObject = (storeMap) =>
  Object.fromEntries(
    Array.from(storeMap.entries()).map(([field, count]) => [
      field,
      Number(count || 0),
    ])
  );

export const incrementUnreadCount = async ({ consumerId, companyId, senderType }) => {
  if (!consumerId || !companyId || !senderType) {
    return;
  }

  const client = getRedisClient();

  try {
    if (client) {
      if (senderType === "consumer") {
        const key = companyHashKey(companyId);
        const field = companyField(consumerId);
        const unreadCount = await client.hIncrBy(key, field, 1);
        const totals = await client.hGetAll(key);

        emitUnreadUpdate(companyRooms(companyId), {
          type: "company",
          consumerId,
          companyId,
          field,
          unreadCount,
          totalUnread: sumValues(totals),
        });
      } else if (senderType === "company") {
        const key = consumerHashKey(consumerId);
        const field = consumerField(companyId);
        const unreadCount = await client.hIncrBy(key, field, 1);
        const totals = await client.hGetAll(key);

        emitUnreadUpdate(consumerRooms(consumerId), {
          type: "consumer",
          consumerId,
          companyId,
          field,
          unreadCount,
          totalUnread: sumValues(totals),
        });
      }
      return;
    }
  } catch (error) {
    console.error("[chat:unread] increment error:", error.message);
  }

  // Fallback logic (in-memory).
  if (senderType === "consumer") {
    const key = companyHashKey(companyId);
    const field = companyField(consumerId);
    const record = getFallbackRecord(fallbackCompanyStore, key);
    const nextCount = (record.get(field) || 0) + 1;
    record.set(field, nextCount);
    emitUnreadUpdate(companyRooms(companyId), {
      type: "company",
      consumerId,
      companyId,
      field,
      unreadCount: nextCount,
      totalUnread: sumValues(fallbackToObject(record)),
    });
  } else if (senderType === "company") {
    const key = consumerHashKey(consumerId);
    const field = consumerField(companyId);
    const record = getFallbackRecord(fallbackConsumerStore, key);
    const nextCount = (record.get(field) || 0) + 1;
    record.set(field, nextCount);
    emitUnreadUpdate(consumerRooms(consumerId), {
      type: "consumer",
      consumerId,
      companyId,
      field,
      unreadCount: nextCount,
      totalUnread: sumValues(fallbackToObject(record)),
    });
  }
};

export const clearUnreadCount = async ({ consumerId, companyId, readerType }) => {
  if (!consumerId || !companyId || !readerType) {
    return;
  }

  const client = getRedisClient();

  try {
    if (client) {
      if (readerType === "consumer") {
        const key = consumerHashKey(consumerId);
        const field = consumerField(companyId);
        await client.hDel(key, field);
        const totals = await client.hGetAll(key);

        emitUnreadUpdate(consumerRooms(consumerId), {
          type: "consumer",
          consumerId,
          companyId,
          field,
          unreadCount: 0,
          totalUnread: sumValues(totals),
        });
      } else if (readerType === "company") {
        const key = companyHashKey(companyId);
        const field = companyField(consumerId);
        await client.hDel(key, field);
        const totals = await client.hGetAll(key);

        emitUnreadUpdate(companyRooms(companyId), {
          type: "company",
          consumerId,
          companyId,
          field,
          unreadCount: 0,
          totalUnread: sumValues(totals),
        });
      }
      return;
    }
  } catch (error) {
    console.error("[chat:unread] clear error:", error.message);
  }

  // Fallback logic (in-memory).
  if (readerType === "consumer") {
    const key = consumerHashKey(consumerId);
    const field = consumerField(companyId);
    const record = getFallbackRecord(fallbackConsumerStore, key);
    record.delete(field);
    emitUnreadUpdate(consumerRooms(consumerId), {
      type: "consumer",
      consumerId,
      companyId,
      field,
      unreadCount: 0,
      totalUnread: sumValues(fallbackToObject(record)),
    });
  } else if (readerType === "company") {
    const key = companyHashKey(companyId);
    const field = companyField(consumerId);
    const record = getFallbackRecord(fallbackCompanyStore, key);
    record.delete(field);
    emitUnreadUpdate(companyRooms(companyId), {
      type: "company",
      consumerId,
      companyId,
      field,
      unreadCount: 0,
      totalUnread: sumValues(fallbackToObject(record)),
    });
  }
};

export const getConsumerUnreadSnapshot = async (consumerId) => {
  if (!consumerId) return {};
  const client = getRedisClient();
  if (client) {
    try {
      const record = await client.hGetAll(consumerHashKey(consumerId));
      return parseCounts(record);
    } catch (error) {
      console.error("[chat:unread] snapshot consumer error:", error.message);
    }
  }

  const record = getFallbackRecord(fallbackConsumerStore, consumerHashKey(consumerId));
  return fallbackToObject(record);
};

export const getCompanyUnreadSnapshot = async (companyId) => {
  if (!companyId) return {};
  const client = getRedisClient();
  if (client) {
    try {
      const record = await client.hGetAll(companyHashKey(companyId));
      return parseCounts(record);
    } catch (error) {
      console.error("[chat:unread] snapshot company error:", error.message);
    }
  }

  const record = getFallbackRecord(fallbackCompanyStore, companyHashKey(companyId));
  return fallbackToObject(record);
};


