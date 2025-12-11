import express from "express";
import { registerCompany, uploadDocuments } from "../controllers/companyRegistrationController.js";

const router = express.Router();

// Company registration (public route, no authentication required)
// Use multer middleware to handle file uploads
router.post("/register", uploadDocuments.array('documents', 10), (req, res, next) => {
    console.log('Multer processed files:', req.files?.length || 0);
    if (req.files) {
        console.log('Files received:', req.files.map(f => ({
            originalname: f.originalname,
            mimetype: f.mimetype,
            size: f.size,
            hasBuffer: !!f.buffer
        })));
    }
    next();
}, registerCompany);

export default router;

