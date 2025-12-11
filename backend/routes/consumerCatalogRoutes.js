import express from "express";
import {
    getCompanies,
    getCompanyProducts,
    requestCompanyAccess,
    getAccessRequests,
    getAccessibleCompanies,
    getConsumerRequestStatus
} from "../controllers/consumerCatalogController.js";
import { authenticateConsumerToken, optionalConsumerAuth } from "../middleware/consumerAuth.js";

const router = express.Router();

// Public routes (companies list)
router.get("/companies", optionalConsumerAuth, getCompanies);

// Protected routes (require consumer authentication)
router.get("/companies-with-status", authenticateConsumerToken, getConsumerRequestStatus);
router.get("/companies/:companyId/products", authenticateConsumerToken, getCompanyProducts);
router.post("/companies/:companyId/request-access", authenticateConsumerToken, requestCompanyAccess);
router.get("/access-requests", authenticateConsumerToken, getAccessRequests);
router.get("/accessible-companies", authenticateConsumerToken, getAccessibleCompanies);

export default router;
