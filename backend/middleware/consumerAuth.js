import jwt from "jsonwebtoken";
import { sql } from "../config/db.js";

const JWT_SECRET = process.env.JWT_SECRET || "your-super-secret-jwt-key-change-in-production";

export const verifyConsumerAccessToken = (token) => {
    try {
        const decoded = jwt.verify(token, JWT_SECRET);
        
        if (decoded.userType !== 'consumer') {
            throw new Error("Invalid token type");
        }
        
        return decoded;
    } catch (error) {
        throw new Error("Invalid access token");
    }
};

export const authenticateConsumerToken = async (req, res, next) => {
    try {
        console.log('=== CONSUMER AUTH MIDDLEWARE DEBUG ===');
        const authHeader = req.headers.authorization;
        console.log('Authorization header:', authHeader);
        const token = authHeader && authHeader.split(' ')[1];
        console.log('Token:', token ? token.substring(0, 20) + '...' : 'NO TOKEN');

        if (!token) {
            console.log('No token provided');
            return res.status(401).json({
                success: false,
                message: "Access token required"
            });
        }

        console.log('Verifying token...');
        const decoded = verifyConsumerAccessToken(token);
        console.log('Token decoded:', decoded);
        
        // Verify consumer still exists and is active
        console.log('Looking up consumer with ID:', decoded.consumerId);
        const consumer = await sql`
            SELECT id, email, first_name, last_name, phone, is_active
            FROM consumer_users 
            WHERE id = ${decoded.consumerId} AND is_active = true
        `;
        console.log('Consumer lookup result:', consumer);

        if (consumer.length === 0) {
            console.log('Consumer not found or inactive');
            return res.status(401).json({
                success: false,
                message: "Consumer not found or inactive"
            });
        }

        // Attach consumer info to request
        req.consumer = {
            id: consumer[0].id,
            email: consumer[0].email,
            firstName: consumer[0].first_name,
            lastName: consumer[0].last_name,
            phone: consumer[0].phone,
            consumerId: consumer[0].id // For compatibility
        };

        next();

    } catch (error) {
        console.error("Consumer authentication error:", error);
        return res.status(401).json({
            success: false,
            message: "Invalid access token"
        });
    }
};

export const optionalConsumerAuth = async (req, res, next) => {
    try {
        const authHeader = req.headers.authorization;
        const token = authHeader && authHeader.split(' ')[1];

        if (token) {
            try {
                const decoded = verifyConsumerAccessToken(token);
                
                const consumer = await sql`
                    SELECT id, email, first_name, last_name, phone, is_active
                    FROM consumer_users 
                    WHERE id = ${decoded.consumerId} AND is_active = true
                `;

                if (consumer.length > 0) {
                    req.consumer = {
                        id: consumer[0].id,
                        email: consumer[0].email,
                        firstName: consumer[0].first_name,
                        lastName: consumer[0].last_name,
                        phone: consumer[0].phone,
                        consumerId: consumer[0].id
                    };
                }
            } catch (error) {
                // Token is invalid, but we continue without authentication
                console.warn("Optional consumer auth failed:", error.message);
            }
        }

        next();

    } catch (error) {
        console.error("Optional consumer authentication error:", error);
        next(); // Continue even if there's an error
    }
};
