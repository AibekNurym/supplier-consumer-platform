import { 
    verifyAccessToken, 
    verifyStoredRefreshToken, 
    getUserPermissions, 
    hasPermission,
    logUserAction 
} from "../lib/auth.js";
import { sql } from "../config/db.js";

/**
 * Middleware to authenticate JWT access token
 */
export const authenticateToken = async (req, res, next) => {
    try {
        const authHeader = req.headers.authorization;
        const token = authHeader && authHeader.split(" ")[1]; // Bearer TOKEN

        if (!token) {
            return res.status(401).json({ 
                success: false, 
                message: "Access token required" 
            });
        }

        // Verify the token
        const decoded = verifyAccessToken(token);
        
        // Get user details with role information
        const user = await sql`
            SELECT u.*, r.name as role_name, r.description as role_description
            FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE u.id = ${decoded.userId} AND u.is_active = true
        `;

        if (user.length === 0) {
            return res.status(401).json({ 
                success: false, 
                message: "User not found or inactive" 
            });
        }

        // Get user permissions
        const permissions = await getUserPermissions(user[0].role_id);
        
        // Update user's last_active timestamp (track online status)
        await sql`
            UPDATE users 
            SET last_active = CURRENT_TIMESTAMP 
            WHERE id = ${user[0].id}
        `;
        
        // Attach user info to request
        req.user = {
            id: user[0].id,
            email: user[0].email,
            firstName: user[0].first_name,
            lastName: user[0].last_name,
            roleId: user[0].role_id,
            roleName: user[0].role_name,
            roleDescription: user[0].role_description,
            company_id: user[0].company_id,
            permissions: permissions.map(p => p.name)
        };

        next();
    } catch (error) {
        console.error("Authentication error:", error);
        return res.status(401).json({ 
            success: false, 
            message: "Invalid or expired token" 
        });
    }
};

/**
 * Middleware to check if user has specific permission
 */
export const authorizePermission = (permissionName) => {
    return async (req, res, next) => {
        try {
            if (!req.user) {
                return res.status(401).json({ 
                    success: false, 
                    message: "Authentication required" 
                });
            }

            const hasRequiredPermission = await hasPermission(req.user.roleId, permissionName);
            
            if (!hasRequiredPermission) {
                // Log unauthorized access attempt
                await logUserAction(
                    req.user.id, 
                    "unauthorized_access_attempt", 
                    "permission_check", 
                    null, 
                    { permission: permissionName },
                    req
                );
                
                return res.status(403).json({ 
                    success: false, 
                    message: `Insufficient permissions. Required: ${permissionName}` 
                });
            }

            next();
        } catch (error) {
            console.error("Authorization error:", error);
            return res.status(500).json({ 
                success: false, 
                message: "Authorization check failed" 
            });
        }
    };
};

/**
 * Middleware to check if user has any of the specified roles
 */
export const authorizeRoles = (...allowedRoles) => {
    return async (req, res, next) => {
        try {
            if (!req.user) {
                return res.status(401).json({ 
                    success: false, 
                    message: "Authentication required" 
                });
            }

            if (!allowedRoles.includes(req.user.roleName)) {
                // Log unauthorized access attempt
                await logUserAction(
                    req.user.id, 
                    "unauthorized_access_attempt", 
                    "role_check", 
                    null, 
                    { requiredRoles: allowedRoles, userRole: req.user.roleName },
                    req
                );
                
                return res.status(403).json({ 
                    success: false, 
                    message: `Access denied. Required roles: ${allowedRoles.join(", ")}` 
                });
            }

            next();
        } catch (error) {
            console.error("Role authorization error:", error);
            return res.status(500).json({ 
                success: false, 
                message: "Role authorization check failed" 
            });
        }
    };
};

/**
 * Middleware to check if user can manage other users (Owner/Manager only)
 */
export const authorizeUserManagement = async (req, res, next) => {
    try {
        if (!req.user) {
            return res.status(401).json({ 
                success: false, 
                message: "Authentication required" 
            });
        }

        // Owners can manage anyone
        if (req.user.roleName === "Owner") {
            return next();
        }

        // Managers can only manage Sales Representatives
        if (req.user.roleName === "Manager") {
            const targetUserId = req.params.id || req.body.userId;
            if (targetUserId) {
                const targetUser = await sql`
                    SELECT r.name as role_name
                    FROM users u
                    JOIN roles r ON u.role_id = r.id
                    WHERE u.id = ${targetUserId}
                `;
                
                if (targetUser.length > 0 && targetUser[0].role_name === "Sales Representative") {
                    return next();
                }
            }
        }

        // Log unauthorized access attempt
        await logUserAction(
            req.user.id, 
            "unauthorized_user_management_attempt", 
            "user_management", 
            req.params.id || req.body.userId,
            { userRole: req.user.roleName },
            req
        );

        return res.status(403).json({ 
            success: false, 
            message: "Insufficient permissions to manage this user" 
        });
    } catch (error) {
        console.error("User management authorization error:", error);
        return res.status(500).json({ 
            success: false, 
            message: "User management authorization check failed" 
        });
    }
};

/**
 * Optional authentication middleware (doesn't fail if no token)
 */
export const optionalAuth = async (req, res, next) => {
    try {
        const authHeader = req.headers.authorization;
        const token = authHeader && authHeader.split(" ")[1];

        if (!token) {
            req.user = null;
            return next();
        }

        const decoded = verifyAccessToken(token);
        
        const user = await sql`
            SELECT u.*, r.name as role_name
            FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE u.id = ${decoded.userId} AND u.is_active = true
        `;

        if (user.length > 0) {
            const permissions = await getUserPermissions(user[0].role_id);
            req.user = {
                id: user[0].id,
                email: user[0].email,
                firstName: user[0].first_name,
                lastName: user[0].last_name,
                roleId: user[0].role_id,
                roleName: user[0].role_name,
                company_id: user[0].company_id,
                permissions: permissions.map(p => p.name)
            };
        } else {
            req.user = null;
        }

        next();
    } catch (error) {
        req.user = null;
        next();
    }
};
