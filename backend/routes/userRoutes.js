import express from "express";
import {
    getAllUsers,
    getUserById,
    createUser,
    updateUser,
    deleteUser,
    getAllRoles,
    getRolePermissions,
    getAuditLog,
    deleteCompany
} from "../controllers/userController.js";
import { 
    authenticateToken, 
    authorizeRoles, 
    authorizePermission,
    authorizeUserManagement 
} from "../middleware/auth.js";

const router = express.Router();

// All routes require authentication
router.use(authenticateToken);

// User management routes (Owner and Manager only)
router.get("/", authorizeRoles("Owner", "Manager"), getAllUsers);
router.get("/roles", authorizeRoles("Owner", "Manager"), getAllRoles);
router.get("/roles/:roleId/permissions", authorizeRoles("Owner", "Manager"), getRolePermissions);
router.get("/audit-log", authorizeRoles("Owner", "Manager"), getAuditLog);

// Company management routes (Owner only)
router.delete("/company", authorizeRoles("Owner"), deleteCompany);

// User CRUD operations
router.get("/:id", authorizeUserManagement, getUserById);
router.post("/", authorizeRoles("Owner", "Manager"), createUser);
router.put("/:id", authorizeUserManagement, updateUser);
router.delete("/:id", authorizeUserManagement, deleteUser);

export default router;

