import { sql } from "../config/db.js";
import { hashPassword, comparePassword } from "../lib/auth.js";

/**
 * Get all companies pending approval
 */
export const getPendingCompanies = async (req, res) => {
    try {
        const companies = await sql`
            SELECT 
                c.id,
                c.name,
                c.description,
                c.status,
                c.rejection_message,
                c.business_documents,
                c.created_at,
                u.email as owner_email,
                u.first_name as owner_first_name,
                u.last_name as owner_last_name,
                u.phone as owner_phone
            FROM companies c
            JOIN users u ON c.owner_id = u.id
            ORDER BY c.created_at DESC
        `;

        // Update business_documents to include document IDs for download
        // Handle both old format (with path) and new format (with id from company_documents table)
        const companiesWithDocIds = await Promise.all(companies.map(async (company) => {
            if (company.business_documents && Array.isArray(company.business_documents)) {
                const updatedDocs = await Promise.all(company.business_documents.map(async (doc) => {
                    // If document has id, it's from the new database table
                    if (doc.id) {
                        return {
                            ...doc,
                            downloadUrl: `/api/admin/documents/${doc.id}`
                        };
                    }
                    // Old format: check if document exists in company_documents table
                    // Try to find by originalname and company_id
                    try {
                        const dbDoc = await sql`
                            SELECT id FROM company_documents
                            WHERE company_id = ${company.id} AND originalname = ${doc.originalname}
                            LIMIT 1
                        `;
                        if (dbDoc.length > 0) {
                            return {
                                ...doc,
                                id: dbDoc[0].id,
                                downloadUrl: `/api/admin/documents/${dbDoc[0].id}`
                            };
                        }
                    } catch (error) {
                        // Table might not exist or other error - use old file path
                        console.log('Error checking document in DB:', error.message);
                    }
                    // Fallback to old file path (if file exists on disk)
                    return {
                        ...doc,
                        downloadUrl: doc.path || null,
                        isOldFormat: true
                    };
                }));
                company.business_documents = updatedDocs;
            }
            return company;
        }));

        res.json({
            success: true,
            data: companiesWithDocIds
        });
    } catch (error) {
        console.error("Get pending companies error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Approve a company
 */
export const approveCompany = async (req, res) => {
    try {
        const { companyId } = req.params;

        // Update company status to approved
        const updatedCompany = await sql`
            UPDATE companies
            SET status = 'approved', updated_at = CURRENT_TIMESTAMP
            WHERE id = ${companyId}
            RETURNING id, name, status
        `;

        if (updatedCompany.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Company not found"
            });
        }

        res.json({
            success: true,
            message: "Company approved successfully",
            data: updatedCompany[0]
        });
    } catch (error) {
        console.error("Approve company error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Reject a company
 */
export const rejectCompany = async (req, res) => {
    try {
        const { companyId } = req.params;
        const { rejectionMessage } = req.body;

        if (!rejectionMessage || !rejectionMessage.trim()) {
            return res.status(400).json({
                success: false,
                message: "Rejection message is required"
            });
        }

        // Update company status to rejected with message
        const updatedCompany = await sql`
            UPDATE companies
            SET 
                status = 'rejected',
                rejection_message = ${rejectionMessage.trim()},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ${companyId}
            RETURNING id, name, status, rejection_message
        `;

        if (updatedCompany.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Company not found"
            });
        }

        res.json({
            success: true,
            message: "Company rejected successfully",
            data: updatedCompany[0]
        });
    } catch (error) {
        console.error("Reject company error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Delete rejected company and owner credentials
 */
export const deleteRejectedCompany = async (req, res) => {
    try {
        const { companyId } = req.params;

        // Get company info first
        const company = await sql`
            SELECT id, owner_id, status FROM companies WHERE id = ${companyId}
        `;

        if (company.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Company not found"
            });
        }

        if (company[0].status !== 'rejected') {
            return res.status(400).json({
                success: false,
                message: "Only rejected companies can be deleted"
            });
        }

        // Delete company (CASCADE will delete owner and all related data)
        await sql`DELETE FROM companies WHERE id = ${companyId}`;

        res.json({
            success: true,
            message: "Company and owner credentials deleted successfully"
        });
    } catch (error) {
        console.error("Delete rejected company error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Get admin profile
 */
export const getAdminProfile = async (req, res) => {
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
                message: "Admin not found"
            });
        }

        res.json({
            success: true,
            data: {
                user: {
                    id: user[0].id,
                    email: user[0].email,
                    firstName: user[0].first_name,
                    lastName: user[0].last_name,
                    phone: user[0].phone,
                    roleName: user[0].role_name,
                    roleDescription: user[0].role_description
                }
            }
        });
    } catch (error) {
        console.error("Get admin profile error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Update admin profile
 */
export const updateAdminProfile = async (req, res) => {
    try {
        const { email, firstName, lastName, phone } = req.body;
        const userId = req.user.id;

        // Validation
        if (!firstName || !lastName) {
            return res.status(400).json({
                success: false,
                message: "First name and last name are required"
            });
        }

        // Check if email is being changed and if it's already taken
        const currentUser = await sql`SELECT email FROM users WHERE id = ${userId}`;
        if (email && email !== currentUser[0].email) {
            const existingUser = await sql`SELECT id FROM users WHERE email = ${email}`;
            if (existingUser.length > 0) {
                return res.status(409).json({
                    success: false,
                    message: "Email already in use"
                });
            }
        }

        // Update user
        const updatedUser = await sql`
            UPDATE users 
            SET 
                email = ${email || currentUser[0].email},
                first_name = ${firstName},
                last_name = ${lastName},
                phone = ${phone || null},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ${userId}
            RETURNING id, email, first_name, last_name, phone, updated_at
        `;

        res.json({
            success: true,
            message: "Profile updated successfully",
            data: updatedUser[0]
        });
    } catch (error) {
        console.error("Update admin profile error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

/**
 * Change admin password
 */
export const changeAdminPassword = async (req, res) => {
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
                message: "Admin not found"
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
            SET password_hash = ${newPasswordHash}, updated_at = CURRENT_TIMESTAMP
            WHERE id = ${userId}
        `;

        res.json({
            success: true,
            message: "Password changed successfully"
        });
    } catch (error) {
        console.error("Change admin password error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

