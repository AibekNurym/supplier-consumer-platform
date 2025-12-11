import { authenticateToken } from "./auth.js";

/**
 * Middleware to check if user is an admin
 */
export const isAdmin = (req, res, next) => {
    // First authenticate the token
    authenticateToken(req, res, () => {
        // Check if user role is Admin
        if (req.user && req.user.roleName === 'Admin') {
            next();
        } else {
            return res.status(403).json({
                success: false,
                message: "Access denied. Admin privileges required."
            });
        }
    });
};

