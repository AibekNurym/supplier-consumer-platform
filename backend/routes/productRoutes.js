import express from 'express';
import { 
    createProduct, 
    deleteProduct, 
    getProduct, 
    getProducts, 
    updateProduct,
} from '../controllers/productController.js';
import { 
    authenticateToken, 
    authorizePermission,
    optionalAuth 
} from '../middleware/auth.js';

const router = express.Router();

// Protected routes - require authentication
router.get("/", authenticateToken, authorizePermission("products.read"), getProducts);
router.get("/:id", authenticateToken, authorizePermission("products.read"), getProduct);

// Protected routes
router.post("/", authenticateToken, authorizePermission("products.create"), createProduct);
router.put("/:id", authenticateToken, authorizePermission("products.update"), updateProduct);
router.delete("/:id", authenticateToken, authorizePermission("products.delete"), deleteProduct);

export default router;