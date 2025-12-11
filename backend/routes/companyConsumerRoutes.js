import express from "express";
import {
    getPendingRequests,
    approveRequest,
    rejectRequest,
    getAllRequests,
    getCompanyConsumers,
    revokeAccess,
    blockConsumer,
    unblockConsumer,
    getBlockedConsumers,
    cancelConsumerRequest
} from "../controllers/companyConsumerController.js";
import { authenticateToken, authorizeRoles } from "../middleware/auth.js";

const router = express.Router();

// All routes require authentication
router.use(authenticateToken);

// All routes require Owner or Manager role
router.use(authorizeRoles("Owner", "Manager"));

// Consumer request management routes
router.get("/requests/pending", getPendingRequests);
router.get("/requests", getAllRequests);
router.post("/requests/:requestId/approve", approveRequest);
router.post("/requests/:requestId/reject", rejectRequest);
router.post("/requests/:requestId/cancel", cancelConsumerRequest);

// Consumer access management routes
router.get("/consumers", getCompanyConsumers);
router.post("/consumers/:accessId/revoke", revokeAccess);
router.get("/consumers/blocked", getBlockedConsumers);
router.post("/consumers/:consumerId/block", blockConsumer);
router.post("/consumers/:consumerId/unblock", unblockConsumer);

export default router;


