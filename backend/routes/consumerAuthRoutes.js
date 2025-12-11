import express from "express";
import {
    register,
    login,
    refreshToken,
    logout,
    getProfile
} from "../controllers/consumerAuthController.js";
import { authenticateConsumerToken } from "../middleware/consumerAuth.js";

const router = express.Router();

// Public routes
router.post("/register", register);
router.post("/login", login);
router.post("/refresh-token", refreshToken);
router.post("/logout", logout);

// Protected routes
router.get("/profile", authenticateConsumerToken, getProfile);

export default router;








