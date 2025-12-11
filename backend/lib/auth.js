import jwt from "jsonwebtoken";
import bcrypt from "bcryptjs";
import crypto from "crypto";
import { sql } from "../config/db.js";

// JWT Configuration
const JWT_SECRET = process.env.JWT_SECRET || "your-super-secret-jwt-key-change-in-production";
const JWT_REFRESH_SECRET = process.env.JWT_REFRESH_SECRET || "your-super-secret-refresh-key-change-in-production";
const JWT_EXPIRES_IN = process.env.JWT_EXPIRES_IN || "15m";
const JWT_REFRESH_EXPIRES_IN = process.env.JWT_REFRESH_EXPIRES_IN || "7d";

/**
 * Generate JWT access token
 */
export const generateAccessToken = (payload) => {
    return jwt.sign(payload, JWT_SECRET, { 
        expiresIn: JWT_EXPIRES_IN,
        issuer: "pern-stack-app",
        audience: "pern-stack-users"
    });
};

/**
 * Generate JWT refresh token
 */
export const generateRefreshToken = (payload) => {
    return jwt.sign(payload, JWT_REFRESH_SECRET, { 
        expiresIn: JWT_REFRESH_EXPIRES_IN,
        issuer: "pern-stack-app",
        audience: "pern-stack-users"
    });
};

/**
 * Verify JWT access token
 */
export const verifyAccessToken = (token) => {
    try {
        return jwt.verify(token, JWT_SECRET, {
            issuer: "pern-stack-app",
            audience: "pern-stack-users"
        });
    } catch (error) {
        throw new Error("Invalid access token");
    }
};

/**
 * Verify JWT refresh token
 */
export const verifyRefreshToken = (token) => {
    try {
        return jwt.verify(token, JWT_REFRESH_SECRET, {
            issuer: "pern-stack-app",
            audience: "pern-stack-users"
        });
    } catch (error) {
        throw new Error("Invalid refresh token");
    }
};

/**
 * Hash password using bcrypt
 */
export const hashPassword = async (password) => {
    const saltRounds = 12;
    return await bcrypt.hash(password, saltRounds);
};

/**
 * Compare password with hash
 */
export const comparePassword = async (password, hash) => {
    return await bcrypt.compare(password, hash);
};

/**
 * Generate random token for refresh token storage
 */
export const generateTokenHash = () => {
    return crypto.randomBytes(32).toString("hex");
};

/**
 * Store refresh token in database
 */
export const storeRefreshToken = async (userId, token) => {
    const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
    const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000); // 7 days
    
    await sql`
        INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
        VALUES (${userId}, ${tokenHash}, ${expiresAt})
    `;
    
    return tokenHash;
};

/**
 * Revoke refresh token
 */
export const revokeRefreshToken = async (tokenHash) => {
    await sql`
        UPDATE refresh_tokens 
        SET is_revoked = true 
        WHERE token_hash = ${tokenHash}
    `;
};

/**
 * Verify refresh token exists and is valid
 */
export const verifyStoredRefreshToken = async (tokenHash) => {
    const tokenRecord = await sql`
        SELECT rt.*, u.id as user_id, u.email, u.first_name, u.last_name, u.role_id, r.name as role_name
        FROM refresh_tokens rt
        JOIN users u ON rt.user_id = u.id
        JOIN roles r ON u.role_id = r.id
        WHERE rt.token_hash = ${tokenHash}
        AND rt.is_revoked = false
        AND rt.expires_at > NOW()
    `;
    
    return tokenRecord[0] || null;
};

/**
 * Clean up expired refresh tokens
 */
export const cleanupExpiredTokens = async () => {
    await sql`
        DELETE FROM refresh_tokens 
        WHERE expires_at < NOW() OR is_revoked = true
    `;
};

/**
 * Get user permissions by role
 */
export const getUserPermissions = async (roleId) => {
    const permissions = await sql`
        SELECT p.name, p.resource, p.action, p.description
        FROM permissions p
        JOIN role_permissions rp ON p.id = rp.permission_id
        WHERE rp.role_id = ${roleId}
    `;
    
    return permissions;
};

/**
 * Check if user has specific permission
 */
export const hasPermission = async (roleId, permissionName) => {
    const permission = await sql`
        SELECT p.id
        FROM permissions p
        JOIN role_permissions rp ON p.id = rp.permission_id
        WHERE rp.role_id = ${roleId} AND p.name = ${permissionName}
    `;
    
    return permission.length > 0;
};

/**
 * Log user action for audit trail
 */
export const logUserAction = async (userId, action, resource, resourceId = null, details = null, req = null) => {
    try {
        await sql`
            INSERT INTO audit_log (user_id, action, resource, resource_id, details, ip_address, user_agent)
            VALUES (
                ${userId}, 
                ${action}, 
                ${resource}, 
                ${resourceId}, 
                ${details ? JSON.stringify(details) : null},
                ${req?.ip || req?.connection?.remoteAddress || null},
                ${req?.get('User-Agent') || null}
            )
        `;
    } catch (error) {
        console.error("Failed to log user action:", error);
        // Don't throw error as audit logging shouldn't break the main flow
    }
};


