import { createClient } from "redis";

const DEFAULT_REDIS_URL = process.env.REDIS_URL || "redis://127.0.0.1:6379";

let redisClient = null;
let redisInitialised = false;

export const initRedisClient = async () => {
  if (redisInitialised) {
    return redisClient;
  }

  redisInitialised = true;

  let client;

  try {
    client = createClient({
      url: DEFAULT_REDIS_URL,
      socket: {
        reconnectStrategy(retries) {
          if (retries > 10) {
            return false;
          }
          return Math.min(retries * 100, 3_000);
        },
      },
    });

    client.on("error", (err) => {
      console.error("[redis] client error:", err.message);
    });

    client.on("reconnecting", () => {
      console.warn("[redis] attempting to reconnect...");
    });

    await client.connect();
    console.log("[redis] connected");
    redisClient = client;
  } catch (error) {
    if (client) {
      try {
        await client.disconnect();
      } catch (disconnectError) {
        console.warn(
          `[redis] disconnect cleanup error: ${disconnectError.message}`
        );
      }
    }
    console.warn(
      `[redis] failed to connect (${error.message}). Falling back to in-memory unread counter store.`
    );
    redisClient = null;
  }

  return redisClient;
};

export const getRedisClient = () => redisClient;


