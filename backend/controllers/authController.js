import { 
    hashPassword, 
    comparePassword, 
    generateAccessToken, 
    generateRefreshToken,
    storeRefreshToken,
    revokeRefreshToken,
    verifyStoredRefreshToken,
    logUserAction,
    getUserPermissions
} from "../lib/auth.js";
import { sql } from "../config/db.js";
import crypto from "crypto";

/**
 * Register a new user
 */
export const register = async (req, res) => {
    try {
        const { email, password, firstName, lastName, phone, roleName } = req.body;

        // Validation
        if (!email || !password || !firstName || !lastName) {
            return res.status(400).json({
                success: false,
                message: "Email, password, first name, and last name are required"
            });
        }

        // Check if user already exists
        const existingUser = await sql`
            SELECT id FROM users WHERE email = ${email}
        `;

        if (existingUser.length > 0) {
            return res.status(409).json({
                success: false,
                message: "User with this email already exists"
            });
        }

        // Get role ID and details
        const role = await sql`
            SELECT id, name, description FROM roles WHERE name = ${roleName || 'Owner'}
        `;

        if (role.length === 0) {
            return res.status(400).json({
                success: false,
                message: "Invalid role specified"
            });
        }

        // Hash password
        const passwordHash = await hashPassword(password);

        // Create user
        const newUser = await sql`
            INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, created_by)
            VALUES (${email}, ${passwordHash}, ${firstName}, ${lastName}, ${phone || null}, ${role[0].id}, ${req.user?.id || null})
            RETURNING id, email, first_name, last_name, phone, role_id, created_at
        `;

        // If this is an Owner, create a company for them
        let companyId = null;
        if (role[0].name === 'Owner') {
            const companyName = `${firstName} ${lastName} Company`;
            const companyDescription = `Company owned by ${firstName} ${lastName}`;
            
            const newCompany = await sql`
                INSERT INTO companies (name, description, owner_id)
                VALUES (${companyName}, ${companyDescription}, ${newUser[0].id})
                RETURNING id
            `;
            companyId = newCompany[0].id;
            
            // Update the user with company_id
            await sql`
                UPDATE users 
                SET company_id = ${companyId}
                WHERE id = ${newUser[0].id}
            `;
        }

        // Log the action
        await logUserAction(
            req.user?.id || null,
            "user_registered",
            "users",
            newUser[0].id,
            { email, roleName: roleName || 'Sales Representative' },
            req
        );

        // Get user permissions for the new user
        const permissions = await getUserPermissions(newUser[0].role_id);

        // Generate tokens for automatic login
        const tokenPayload = {
            userId: newUser[0].id,
            email: newUser[0].email,
            roleId: newUser[0].role_id,
            roleName: role[0].name
        };

        const accessToken = generateAccessToken(tokenPayload);
        const refreshToken = generateRefreshToken(tokenPayload);

        // Store refresh token
        await storeRefreshToken(newUser[0].id, refreshToken);

        // Update last login
        await sql`
            UPDATE users 
            SET last_login = CURRENT_TIMESTAMP 
            WHERE id = ${newUser[0].id}
        `;

        res.status(201).json({
            success: true,
            message: "User registered successfully",
            data: {
                user: {
                    id: newUser[0].id,
                    email: newUser[0].email,
                    firstName: newUser[0].first_name,
                    lastName: newUser[0].last_name,
                    phone: newUser[0].phone,
                    roleId: newUser[0].role_id,
                    roleName: role[0].name,
                    roleDescription: role[0].description,
                    company_id: companyId,
                    permissions: permissions,
                    lastLogin: new Date().toISOString()
                },
                tokens: {
                    accessToken,
                    refreshToken
                }
            }
        });

    } catch (error) {
        console.error("Registration error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error during registration"
        });
    }
};

/**
 * Login user
 */
export const login = async (req, res) => {
    try {
        const { email, password } = req.body;

        // Validation
        if (!email || !password) {
            return res.status(400).json({
                success: false,
                message: "Email and password are required"
            });
        }

        // Find user with role and company information
        const user = await sql`
            SELECT u.*, r.name as role_name, r.description as role_description, 
                   c.is_active as company_active, c.status as company_status, c.rejection_message
            FROM users u
            JOIN roles r ON u.role_id = r.id
            LEFT JOIN companies c ON u.company_id = c.id
            WHERE u.email = ${email} AND u.is_active = true
        `;

        if (user.length === 0) {
            return res.status(401).json({
                success: false,
                message: "Invalid credentials"
            });
        }

        // Check company status for Owners (not for Admin users)
        if (user[0].role_name === 'Owner' && user[0].company_id) {
            const companyStatus = user[0].company_status;
            
            if (companyStatus === 'pending') {
                return res.status(403).json({
                    success: false,
                    message: "Wait until the admin approves you.",
                    companyStatus: 'pending'
                });
            }
            
            if (companyStatus === 'rejected') {
                const rejectionMessage = user[0].rejection_message || 'No reason provided';
                return res.status(403).json({
                    success: false,
                    message: `Your company is kinda lame! ${rejectionMessage}`,
                    companyStatus: 'rejected',
                    rejectionMessage: rejectionMessage
                });
            }
        }

        // Check if company is active (if user belongs to a company)
        if (user[0].company_id && !user[0].company_active) {
            return res.status(401).json({
                success: false,
                message: "Account is deactivated. Please contact your administrator."
            });
        }

        // Verify password
        const isValidPassword = await comparePassword(password, user[0].password_hash);
        if (!isValidPassword) {
            return res.status(401).json({
                success: false,
                message: "Invalid credentials"
            });
        }

        // Get user permissions
        const permissions = await getUserPermissions(user[0].role_id);

        // Generate tokens
        const tokenPayload = {
            userId: user[0].id,
            email: user[0].email,
            roleId: user[0].role_id,
            roleName: user[0].role_name
        };

        const accessToken = generateAccessToken(tokenPayload);
        const refreshToken = generateRefreshToken(tokenPayload);

        // Store refresh token
        await storeRefreshToken(user[0].id, refreshToken);

        // Update last login
        await sql`
            UPDATE users 
            SET last_login = NOW() 
            WHERE id = ${user[0].id}
        `;

        // Log the action
        await logUserAction(
            user[0].id,
            "user_login",
            "auth",
            null,
            { email },
            req
        );

        res.json({
            success: true,
            message: "Login successful",
            data: {
                user: {
                    id: user[0].id,
                    email: user[0].email,
                    firstName: user[0].first_name,
                    lastName: user[0].last_name,
                    phone: user[0].phone,
                    roleId: user[0].role_id,
                    roleName: user[0].role_name,
                    roleDescription: user[0].role_description,
                    company_id: user[0].company_id,
                    permissions: permissions.map(p => p.name),
                    lastLogin: user[0].last_login
                },
                tokens: {
                    accessToken,
                    refreshToken
                }
            }
        });

    } catch (error) {
        console.error("Login error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error during login"
        });
    }
};

/**
 * Refresh access token
 */
export const refreshToken = async (req, res) => {
    try {
        const { refreshToken } = req.body;

        if (!refreshToken) {
            return res.status(400).json({
                success: false,
                message: "Refresh token is required"
            });
        }

        // Verify stored refresh token
        const tokenHash = crypto.createHash("sha256").update(refreshToken).digest("hex");
        const tokenRecord = await verifyStoredRefreshToken(tokenHash);
        if (!tokenRecord) {
            return res.status(401).json({
                success: false,
                message: "Invalid or expired refresh token"
            });
        }

        // Generate new access token
        const tokenPayload = {
            userId: tokenRecord.user_id,
            email: tokenRecord.email,
            roleId: tokenRecord.role_id,
            roleName: tokenRecord.role_name
        };

        const newAccessToken = generateAccessToken(tokenPayload);

        // Log the action
        await logUserAction(
            tokenRecord.user_id,
            "token_refreshed",
            "auth",
            null,
            null,
            req
        );

        res.json({
            success: true,
            message: "Token refreshed successfully",
            data: {
                accessToken: newAccessToken,
                refreshToken
            }
        });

    } catch (error) {
        console.error("Token refresh error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error during token refresh"
        });
    }
};

/**
 * Logout user
 */
export const logout = async (req, res) => {
    try {
        const { refreshToken } = req.body;

        if (refreshToken) {
            // Revoke refresh token
            const tokenHash = crypto.createHash("sha256").update(refreshToken).digest("hex");
            await revokeRefreshToken(tokenHash);
        }

        // Log the action only if user still exists
        if (req.user && req.user.id) {
            try {
                await logUserAction(
                    req.user.id,
                    "user_logout",
                    "auth",
                    null,
                    null,
                    req
                );
            } catch (logError) {
                // If logging fails (e.g., user was deleted), continue with logout
                console.warn("Failed to log logout action:", logError.message);
            }
        }

        res.json({
            success: true,
            message: "Logout successful"
        });

    } catch (error) {
        console.error("Logout error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error during logout"
        });
    }
};

/**
 * Get current user profile
 */
export const getProfile = async (req, res) => {
    try {
        const user = await sql`
            SELECT u.*, r.name as role_name, r.description as role_description
            FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE u.id = ${req.user.id}
        `;

        if (user.length === 0) {
            return res.status(404).json({
                success: false,
                message: "User not found"
            });
        }

        const permissions = await getUserPermissions(user[0].role_id);

        res.json({
            success: true,
            data: {
                user: {
                    id: user[0].id,
                    email: user[0].email,
                    firstName: user[0].first_name,
                    lastName: user[0].last_name,
                    phone: user[0].phone,
                    roleId: user[0].role_id,
                    roleName: user[0].role_name,
                    roleDescription: user[0].role_description,
                    company_id: user[0].company_id,
                    permissions: permissions.map(p => p.name),
                    isActive: user[0].is_active,
                    lastLogin: user[0].last_login,
                    createdAt: user[0].created_at
                }
            }
        });

    } catch (error) {
        console.error("Get profile error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Update user profile
 */
export const updateProfile = async (req, res) => {
    try {
        const { firstName, lastName, phone } = req.body;
        const userId = req.user.id;

        // Validation
        if (!firstName || !lastName) {
            return res.status(400).json({
                success: false,
                message: "First name and last name are required"
            });
        }

        // Update user
        const updatedUser = await sql`
            UPDATE users 
            SET 
                first_name = ${firstName},
                last_name = ${lastName},
                phone = ${phone || null},
                updated_at = NOW()
            WHERE id = ${userId}
            RETURNING id, email, first_name, last_name, phone, updated_at
        `;

        // Log the action
        await logUserAction(
            userId,
            "profile_updated",
            "users",
            userId,
            { firstName, lastName, phone },
            req
        );

        res.json({
            success: true,
            message: "Profile updated successfully",
            data: updatedUser[0]
        });

    } catch (error) {
        console.error("Update profile error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Change password
 */
export const changePassword = async (req, res) => {
    try {
        const { currentPassword, newPassword } = req.body;
        const userId = req.user.id;

        // Validation
        if (!currentPassword || !newPassword) {
            return res.status(400).json({
                success: false,
                message: "Current password and new password are required"
            });
        }

        // Get current user
        const user = await sql`
            SELECT password_hash FROM users WHERE id = ${userId}
        `;

        if (user.length === 0) {
            return res.status(404).json({
                success: false,
                message: "User not found"
            });
        }

        // Verify current password
        const isValidPassword = await comparePassword(currentPassword, user[0].password_hash);
        if (!isValidPassword) {
            return res.status(401).json({
                success: false,
                message: "Current password is incorrect"
            });
        }

        // Hash new password
        const newPasswordHash = await hashPassword(newPassword);

        // Update password
        await sql`
            UPDATE users 
            SET password_hash = ${newPasswordHash}, updated_at = NOW()
            WHERE id = ${userId}
        `;

        // Log the action
        await logUserAction(
            userId,
            "password_changed",
            "users",
            userId,
            null,
            req
        );

        res.json({
            success: true,
            message: "Password changed successfully"
        });

    } catch (error) {
        console.error("Change password error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};
