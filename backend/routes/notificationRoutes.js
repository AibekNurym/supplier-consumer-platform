import { Router } from "express";
import { authenticateToken } from "../middleware/auth.js";
import { authenticateConsumerToken } from "../middleware/consumerAuth.js";
import {
  createNotificationForTesting,
  getCompanyNotificationFeed,
  getConsumerNotificationFeed,
  markCompanyNotificationFeedRead,
  markConsumerNotificationFeedRead,
} from "../controllers/notificationController.js";

const router = Router();

router.get(
  "/consumer",
  authenticateConsumerToken,
  getConsumerNotificationFeed
);
router.post(
  "/consumer/mark-read",
  authenticateConsumerToken,
  markConsumerNotificationFeedRead
);

router.get(
  "/company",
  authenticateToken,
  getCompanyNotificationFeed
);
router.post(
  "/company/mark-read",
  authenticateToken,
  markCompanyNotificationFeedRead
);

// Optional helper for manual testing (protected by company auth)
router.post(
  "/debug/create",
  authenticateToken,
  createNotificationForTesting
);

export default router;



