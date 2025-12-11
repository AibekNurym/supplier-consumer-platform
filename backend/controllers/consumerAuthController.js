import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import crypto from "crypto";
import { sql } from "../config/db.js";

const JWT_SECRET = process.env.JWT_SECRET || "your-super-secret-jwt-key-change-in-production";
const JWT_REFRESH_SECRET = process.env.JWT_REFRESH_SECRET || "your-super-secret-refresh-key-change-in-production";

// Helper functions
const hashPassword = async (password) => {
    return await bcrypt.hash(password, 12);
};

const comparePassword = async (password, hash) => {
    return await bcrypt.compare(password, hash);
};

const generateAccessToken = (payload) => {
    return jwt.sign(payload, JWT_SECRET, { expiresIn: "15m" });
};

const generateRefreshToken = (payload) => {
    return jwt.sign(payload, JWT_REFRESH_SECRET, { expiresIn: "7d" });
};

const storeRefreshToken = async (consumerId, refreshToken) => {
    const tokenHash = crypto.createHash("sha256").update(refreshToken).digest("hex");
    const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000); // 7 days

    await sql`
        INSERT INTO consumer_refresh_tokens (consumer_id, token_hash, expires_at)
        VALUES (${consumerId}, ${tokenHash}, ${expiresAt})
    `;
};

const revokeRefreshToken = async (tokenHash) => {
    await sql`
        UPDATE consumer_refresh_tokens 
        SET is_revoked = true 
        WHERE token_hash = ${tokenHash}
    `;
};

const verifyStoredRefreshToken = async (tokenHash) => {
    const token = await sql`
        SELECT c.*, crt.expires_at, crt.is_revoked
        FROM consumer_refresh_tokens crt
        JOIN consumer_users c ON crt.consumer_id = c.id
        WHERE crt.token_hash = ${tokenHash}
    `;

    if (token.length === 0 || token[0].is_revoked || new Date() > new Date(token[0].expires_at)) {
        throw new Error("Invalid or expired refresh token");
    }

    return token[0];
};

// Consumer Registration
export const register = async (req, res) => {
    try {
        const { email, password, firstName, lastName, phone } = req.body;

        // Validation
        if (!email || !password || !firstName || !lastName) {
            return res.status(400).json({
                success: false,
                message: "Email, password, first name, and last name are required"
            });
        }

        if (password.length < 6) {
            return res.status(400).json({
                success: false,
                message: "Password must be at least 6 characters long"
            });
        }

        // Check if consumer already exists
        const existingConsumer = await sql`
            SELECT id FROM consumer_users WHERE email = ${email}
        `;

        if (existingConsumer.length > 0) {
            return res.status(409).json({
                success: false,
                message: "Consumer with this email already exists"
            });
        }

        // Hash password
        const passwordHash = await hashPassword(password);

        // Create consumer
        const newConsumer = await sql`
            INSERT INTO consumer_users (email, password_hash, first_name, last_name, phone)
            VALUES (${email}, ${passwordHash}, ${firstName}, ${lastName}, ${phone || null})
            RETURNING id, email, first_name, last_name, phone, created_at
        `;

        // Generate tokens
        const tokenPayload = {
            consumerId: newConsumer[0].id,
            email: newConsumer[0].email,
            userType: 'consumer'
        };

        const accessToken = generateAccessToken(tokenPayload);
        const refreshToken = generateRefreshToken(tokenPayload);
        await storeRefreshToken(newConsumer[0].id, refreshToken);

        res.status(201).json({
            success: true,
            message: "Consumer registered successfully",
            data: {
                consumer: {
                    id: newConsumer[0].id,
                    email: newConsumer[0].email,
                    firstName: newConsumer[0].first_name,
                    lastName: newConsumer[0].last_name,
                    phone: newConsumer[0].phone,
                    createdAt: newConsumer[0].created_at
                },
                tokens: {
                    accessToken,
                    refreshToken
                }
            }
        });

    } catch (error) {
        console.error("Consumer registration error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error during registration"
        });
    }
};

// Consumer Login
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

        // Find consumer
        const consumer = await sql`
            SELECT * FROM consumer_users 
            WHERE email = ${email} AND is_active = true
        `;

        if (consumer.length === 0) {
            return res.status(401).json({
                success: false,
                message: "Invalid credentials"
            });
        }

        // Verify password
        const isValidPassword = await comparePassword(password, consumer[0].password_hash);
        if (!isValidPassword) {
            return res.status(401).json({
                success: false,
                message: "Invalid credentials"
            });
        }

        // Generate tokens
        const tokenPayload = {
            consumerId: consumer[0].id,
            email: consumer[0].email,
            userType: 'consumer'
        };

        const accessToken = generateAccessToken(tokenPayload);
        const refreshToken = generateRefreshToken(tokenPayload);
        await storeRefreshToken(consumer[0].id, refreshToken);

        // Update last login
        await sql`
            UPDATE consumer_users 
            SET last_login = CURRENT_TIMESTAMP 
            WHERE id = ${consumer[0].id}
        `;

        res.json({
            success: true,
            message: "Login successful",
            data: {
                consumer: {
                    id: consumer[0].id,
                    email: consumer[0].email,
                    firstName: consumer[0].first_name,
                    lastName: consumer[0].last_name,
                    phone: consumer[0].phone,
                    isActive: consumer[0].is_active,
                    lastLogin: consumer[0].last_login,
                    createdAt: consumer[0].created_at
                },
                tokens: {
                    accessToken,
                    refreshToken
                }
            }
        });

    } catch (error) {
        console.error("Consumer login error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};

// Refresh Token
export const refreshToken = async (req, res) => {
    try {
        const { refreshToken } = req.body;

        if (!refreshToken) {
            return res.status(400).json({
                success: false,
                message: "Refresh token is required"
            });
        }

        const tokenHash = crypto.createHash("sha256").update(refreshToken).digest("hex");
        const consumer = await verifyStoredRefreshToken(tokenHash);

        // Generate new tokens
        const tokenPayload = {
            consumerId: consumer.id,
            email: consumer.email,
            userType: 'consumer'
        };

        const newAccessToken = generateAccessToken(tokenPayload);
        const newRefreshToken = generateRefreshToken(tokenPayload);

        // Revoke old refresh token and store new one
        await revokeRefreshToken(tokenHash);
        await storeRefreshToken(consumer.id, newRefreshToken);

        res.json({
            success: true,
            data: {
                tokens: {
                    accessToken: newAccessToken,
                    refreshToken: newRefreshToken
                }
            }
        });

    } catch (error) {
        console.error("Token refresh error:", error);
        res.status(401).json({
            success: false,
            message: "Invalid refresh token"
        });
    }
};

// Logout
export const logout = async (req, res) => {
    try {
        const { refreshToken } = req.body;

        if (refreshToken) {
            const tokenHash = crypto.createHash("sha256").update(refreshToken).digest("hex");
            await revokeRefreshToken(tokenHash);
        }

        res.json({
            success: true,
            message: "Logout successful"
        });

    } catch (error) {
        console.error("Consumer logout error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error during logout"
        });
    }
};

// Get Consumer Profile
export const getProfile = async (req, res) => {
    try {
        const consumer = await sql`
            SELECT id, email, first_name, last_name, phone, is_active, last_login, created_at
            FROM consumer_users 
            WHERE id = ${req.consumer.consumerId}
        `;

        if (consumer.length === 0) {
            return res.status(404).json({
                success: false,
                message: "Consumer not found"
            });
        }

        res.json({
            success: true,
            data: {
                consumer: {
                    id: consumer[0].id,
                    email: consumer[0].email,
                    firstName: consumer[0].first_name,
                    lastName: consumer[0].last_name,
                    phone: consumer[0].phone,
                    isActive: consumer[0].is_active,
                    lastLogin: consumer[0].last_login,
                    createdAt: consumer[0].created_at
                }
            }
        });

    } catch (error) {
        console.error("Get consumer profile error:", error);
        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
};
