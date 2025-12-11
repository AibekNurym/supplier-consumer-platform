import { 
    hashPassword, 
    logUserAction,
    getUserPermissions
} from "../lib/auth.js";
import { sql } from "../config/db.js";

/**
 * Get all users (with role information)
 */
export const getAllUsers = async (req, res) => {
    try {
        const users = await sql`
            SELECT 
                u.id, u.email, u.first_name, u.last_name, u.phone, 
                u.is_active, u.last_login, u.created_at, u.updated_at,
                r.name as role_name, r.description as role_description,
                c.name as company_name,
                creator.first_name as created_by_first_name,
                creator.last_name as created_by_last_name
            FROM users u
            JOIN roles r ON u.role_id = r.id
            LEFT JOIN companies c ON u.company_id = c.id
            LEFT JOIN users creator ON u.created_by = creator.id
            WHERE u.company_id = ${req.user.company_id}
            ORDER BY u.created_at DESC
        `;

        res.json({
            success: true,
            data: users
        });

    } catch (error) {
        console.error("Get all users error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Get user by ID
 */
export const getUserById = async (req, res) => {
    try {
        const { id } = req.params;

        const user = await sql`
            SELECT 
                u.*, r.name as role_name, r.description as role_description,
                creator.first_name as created_by_first_name,
                creator.last_name as created_by_last_name
            FROM users u
            JOIN roles r ON u.role_id = r.id
            LEFT JOIN users creator ON u.created_by = creator.id
            WHERE u.id = ${id} AND u.company_id = ${req.user.company_id}
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
                ...user[0],
                permissions: permissions.map(p => p.name)
            }
        });

    } catch (error) {
        console.error("Get user by ID error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Create a new user (by Owner/Manager)
 */
export const createUser = async (req, res) => {
    try {
        const { email, password, firstName, lastName, phone, roleName } = req.body;

        // Validation
        if (!email || !password || !firstName || !lastName || !roleName) {
            return res.status(400).json({
                success: false,
                message: "All fields are required"
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

        // Get role ID
        const role = await sql`
            SELECT id FROM roles WHERE name = ${roleName}
        `;

        if (role.length === 0) {
            return res.status(400).json({
                success: false,
                message: "Invalid role specified"
            });
        }

        // Check role assignment permissions
        if (req.user.roleName === "Manager" && roleName === "Owner") {
            return res.status(403).json({
                success: false,
                message: "Managers cannot create Owner accounts"
            });
        }
        
        if (req.user.roleName === "Manager" && roleName === "Manager") {
            return res.status(403).json({
                success: false,
                message: "Managers cannot create other Manager accounts"
            });
        }

        // Hash password
        const passwordHash = await hashPassword(password);

        // Create user
        const newUser = await sql`
            INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, company_id, created_by)
            VALUES (${email}, ${passwordHash}, ${firstName}, ${lastName}, ${phone || null}, ${role[0].id}, ${req.user.company_id}, ${req.user.id})
            RETURNING id, email, first_name, last_name, phone, role_id, company_id, created_at
        `;

        // Log the action
        await logUserAction(
            req.user.id,
            "user_created",
            "users",
            newUser[0].id,
            { email, roleName, createdFor: `${firstName} ${lastName}` },
            req
        );

        res.status(201).json({
            success: true,
            message: "User created successfully",
            data: newUser[0]
        });

    } catch (error) {
        console.error("Create user error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Update user information
 */
export const updateUser = async (req, res) => {
    try {
        const { id } = req.params;
        const { firstName, lastName, phone, roleName, isActive, password } = req.body;

        // Check if user exists and belongs to the same company
        const existingUser = await sql`
            SELECT u.*, r.name as current_role_name
            FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE u.id = ${id} AND u.company_id = ${req.user.company_id}
        `;

        if (existingUser.length === 0) {
            return res.status(404).json({
                success: false,
                message: "User not found"
            });
        }

        // Check permissions for role changes
        if (roleName && roleName !== existingUser[0].current_role_name) {
            if (req.user.roleName === "Manager" && roleName === "Owner") {
                return res.status(403).json({
                    success: false,
                    message: "Managers cannot assign Owner role"
                });
            }

            if (req.user.roleName === "Manager" && existingUser[0].current_role_name === "Owner") {
                return res.status(403).json({
                    success: false,
                    message: "Managers cannot modify Owner accounts"
                });
            }
            
            if (req.user.roleName === "Manager" && existingUser[0].current_role_name === "Manager") {
                return res.status(403).json({
                    success: false,
                    message: "Managers cannot modify other Manager accounts"
                });
            }
            
            if (req.user.roleName === "Manager" && roleName === "Manager") {
                return res.status(403).json({
                    success: false,
                    message: "Managers cannot assign Manager role"
                });
            }
        }

        // Get new role ID if role is being changed
        let roleId = existingUser[0].role_id;
        if (roleName && roleName !== existingUser[0].current_role_name) {
            const role = await sql`
                SELECT id FROM roles WHERE name = ${roleName}
            `;

            if (role.length === 0) {
                return res.status(400).json({
                    success: false,
                    message: "Invalid role specified"
                });
            }

            roleId = role[0].id;
        }

        // Hash password if provided
        let passwordHash = existingUser[0].password_hash;
        if (password && password.trim() !== '') {
            passwordHash = await hashPassword(password);
        }

        // Update user
        const updatedUser = await sql`
            UPDATE users 
            SET 
                first_name = ${firstName || existingUser[0].first_name},
                last_name = ${lastName || existingUser[0].last_name},
                phone = ${phone !== undefined ? phone : existingUser[0].phone},
                role_id = ${roleId},
                password_hash = ${passwordHash},
                is_active = ${isActive !== undefined ? isActive : existingUser[0].is_active},
                updated_at = NOW()
            WHERE id = ${id}
            RETURNING id, email, first_name, last_name, phone, role_id, is_active, updated_at
        `;

        // Log the action
        await logUserAction(
            req.user.id,
            "user_updated",
            "users",
            parseInt(id),
            { 
                updatedFields: { firstName, lastName, phone, roleName, isActive },
                targetUser: `${existingUser[0].first_name} ${existingUser[0].last_name}`
            },
            req
        );

        res.json({
            success: true,
            message: "User updated successfully",
            data: updatedUser[0]
        });

    } catch (error) {
        console.error("Update user error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Delete user
 */
export const deleteUser = async (req, res) => {
    try {
        const { id } = req.params;

        // Check if user exists and belongs to the same company
        const user = await sql`
            SELECT u.*, r.name as role_name
            FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE u.id = ${id} AND u.company_id = ${req.user.company_id}
        `;

        if (user.length === 0) {
            return res.status(404).json({
                success: false,
                message: "User not found"
            });
        }

        // Check permissions
        if (req.user.roleName === "Manager" && user[0].role_name === "Owner") {
            return res.status(403).json({
                success: false,
                message: "Managers cannot delete Owner accounts"
            });
        }
        
        if (req.user.roleName === "Manager" && user[0].role_name === "Manager") {
            return res.status(403).json({
                success: false,
                message: "Managers cannot delete other Manager accounts"
            });
        }

        // Prevent self-deletion
        if (parseInt(id) === req.user.id) {
            return res.status(400).json({
                success: false,
                message: "You cannot delete your own account"
            });
        }

        // Delete user (audit logs will be preserved with user_id set to NULL due to ON DELETE SET NULL)
        await sql`
            DELETE FROM users WHERE id = ${id}
        `;

        // Log the action
        await logUserAction(
            req.user.id,
            "user_deleted",
            "users",
            parseInt(id),
            { 
                deletedUser: `${user[0].first_name} ${user[0].last_name}`,
                deletedUserRole: user[0].role_name
            },
            req
        );

        res.json({
            success: true,
            message: "User deleted successfully"
        });

    } catch (error) {
        console.error("Delete user error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Get all roles
 */
export const getAllRoles = async (req, res) => {
    try {
        const roles = await sql`
            SELECT r.*, 
                   COUNT(u.id) as user_count
            FROM roles r
            LEFT JOIN users u ON r.id = u.role_id AND u.is_active = true
            GROUP BY r.id, r.name, r.description, r.created_at, r.updated_at
            ORDER BY r.name
        `;

        res.json({
            success: true,
            data: roles
        });

    } catch (error) {
        console.error("Get all roles error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Get role permissions
 */
export const getRolePermissions = async (req, res) => {
    try {
        const { roleId } = req.params;

        const permissions = await sql`
            SELECT p.*
            FROM permissions p
            JOIN role_permissions rp ON p.id = rp.permission_id
            WHERE rp.role_id = ${roleId}
            ORDER BY p.resource, p.action
        `;

        res.json({
            success: true,
            data: permissions
        });

    } catch (error) {
        console.error("Get role permissions error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Get audit log
 */
export const getAuditLog = async (req, res) => {
    try {
        const { page = 1, limit = 50, userId, action, resource } = req.query;
        const offset = (page - 1) * limit;

        let whereClause = "1=1";
        const params = [];

        if (userId) {
            whereClause += " AND al.user_id = $" + (params.length + 1);
            params.push(userId);
        }

        if (action) {
            whereClause += " AND al.action = $" + (params.length + 1);
            params.push(action);
        }

        if (resource) {
            whereClause += " AND al.resource = $" + (params.length + 1);
            params.push(resource);
        }

        const auditLogs = await sql`
            SELECT 
                al.*,
                u.first_name as user_first_name,
                u.last_name as user_last_name,
                u.email as user_email
            FROM audit_log al
            LEFT JOIN users u ON al.user_id = u.id
            WHERE ${sql.unsafe(whereClause, params)}
            ORDER BY al.created_at DESC
            LIMIT ${limit} OFFSET ${offset}
        `;

        const totalCount = await sql`
            SELECT COUNT(*) as count
            FROM audit_log al
            WHERE ${sql.unsafe(whereClause, params)}
        `;

        res.json({
            success: true,
            data: {
                logs: auditLogs,
                pagination: {
                    page: parseInt(page),
                    limit: parseInt(limit),
                    total: parseInt(totalCount[0].count),
                    pages: Math.ceil(totalCount[0].count / limit)
                }
            }
        });

    } catch (error) {
        console.error("Get audit log error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Delete company and all associated data
 */
export const deleteCompany = async (req, res) => {
    try {
        // Only Owners can deactivate their company
        if (req.user.roleName !== "Owner") {
            return res.status(403).json({
                success: false,
                message: "Only company owners can deactivate their company"
            });
        }

        const companyId = req.user.company_id;
        
        if (!companyId) {
            return res.status(400).json({
                success: false,
                message: "No company associated with this account"
            });
        }

        // Get company info for logging
        const company = await sql`
            SELECT name, is_active FROM companies WHERE id = ${companyId}
        `;

        if (company.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Company not found"
            });
        }

        // Check if company is already deactivated
        if (!company[0].is_active) {
            return res.status(400).json({
                success: false,
                message: "Company is already deactivated"
            });
        }

        // Log the action before deactivation
        await logUserAction(
            req.user.id,
            "company_deactivated",
            "companies",
            companyId,
            { 
                companyName: company[0].name,
                deactivatedBy: `${req.user.firstName} ${req.user.lastName}`
            },
            req
        );

        // Deactivate the company and all its users
        await sql`
            UPDATE companies 
            SET is_active = false, updated_at = NOW()
            WHERE id = ${companyId}
        `;

        // Deactivate all users in the company
        await sql`
            UPDATE users 
            SET is_active = false, updated_at = NOW()
            WHERE company_id = ${companyId}
        `;

        res.json({
            success: true,
            message: "Company has been deactivated. All users can no longer log in, but data is preserved."
        });

    } catch (error) {
        console.error("Deactivate company error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

