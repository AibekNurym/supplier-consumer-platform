import express from "express";
import rateLimit from "express-rate-limit";
import {
    register,
    login,
    refreshToken,
    logout,
    getProfile,
    updateProfile,
    changePassword
} from "../controllers/authController.js";
import { authenticateToken } from "../middleware/auth.js";

const router = express.Router();

// Rate limiting for auth endpoints
const authLimiter = rateLimit({
    windowMs: 15 * 60 * 1000, // 15 minutes
    max: 5, // limit each IP to 5 requests per windowMs
    message: {
        success: false,
        message: "Too many authentication attempts, please try again later"
    },
    standardHeaders: true,
    legacyHeaders: false,
});

const strictAuthLimiter = rateLimit({
    windowMs: 15 * 60 * 1000, // 15 minutes
    max: 3, // limit each IP to 3 requests per windowMs
    message: {
        success: false,
        message: "Too many attempts, please try again later"
    },
    standardHeaders: true,
    legacyHeaders: false,
});

// Public routes (rate limiting disabled for development)
router.post("/register", register);
router.post("/login", login);
router.post("/refresh-token", refreshToken);

// Protected routes (rate limiting disabled for development)
router.post("/logout", authenticateToken, logout);
router.get("/profile", authenticateToken, getProfile);
router.put("/profile", authenticateToken, updateProfile);
router.put("/change-password", authenticateToken, changePassword);

export default router;

