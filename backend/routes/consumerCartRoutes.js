import express from "express";
import {
    getCart,
    addToCart,
    updateCartItem,
    removeFromCart,
    clearCart,
    checkout
} from "../controllers/consumerCartController.js";
import { authenticateConsumerToken } from "../middleware/consumerAuth.js";

const router = express.Router();

// All routes require consumer authentication
router.get("/company/:companyId", authenticateConsumerToken, getCart);
router.post("/company/:companyId/product/:productId/add", authenticateConsumerToken, addToCart);
router.put("/item/:itemId", authenticateConsumerToken, updateCartItem);
router.delete("/item/:itemId", authenticateConsumerToken, removeFromCart);
router.delete("/company/:companyId/clear", authenticateConsumerToken, clearCart);
router.post("/company/:companyId/checkout", authenticateConsumerToken, checkout);

export default router;

