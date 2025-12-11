import express from "express";
import {
    getPendingCompanies,
    approveCompany,
    rejectCompany,
    deleteRejectedCompany,
    getAdminProfile,
    updateAdminProfile,
    changeAdminPassword
} from "../controllers/adminController.js";
import { downloadDocument, getDocumentInfo } from "../controllers/companyDocumentController.js";
import { isAdmin } from "../middleware/adminAuth.js";

const router = express.Router();

// All routes require admin authentication
router.use(isAdmin);

// Company management
router.get("/companies", getPendingCompanies);
router.put("/companies/:companyId/approve", approveCompany);
router.put("/companies/:companyId/reject", rejectCompany);
router.delete("/companies/:companyId", deleteRejectedCompany);

// Admin profile management
router.get("/profile", getAdminProfile);
router.put("/profile", updateAdminProfile);
router.put("/change-password", changeAdminPassword);

// Document downloads
router.get("/documents/:documentId", downloadDocument);
router.get("/documents/:documentId/info", getDocumentInfo);

export default router;

