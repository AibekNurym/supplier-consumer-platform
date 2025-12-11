/**
 * Copyright (c) 2025 [Your Name]
 * 
 * This file is part of the PERN Stack Application.
 * All rights reserved.
 */

import express from "express";
import http from "http";
import helmet from "helmet";
import morgan from "morgan";
import cors from "cors";
import dotenv from "dotenv";
import { fileURLToPath } from "url";
import { dirname, join } from "path";

import productRoutes from "./routes/productRoutes.js";
import authRoutes from "./routes/authRoutes.js";
import userRoutes from "./routes/userRoutes.js";
import debugRoutes from "./routes/debugRoutes.js";
import consumerAuthRoutes from "./routes/consumerAuthRoutes.js";
import consumerCatalogRoutes from "./routes/consumerCatalogRoutes.js";
import companyConsumerRoutes from "./routes/companyConsumerRoutes.js";
import consumerCartRoutes from "./routes/consumerCartRoutes.js";
import chatRoutes from "./routes/chatRoutes.js";
import orderRoutes from "./routes/orderRoutes.js";
import issueRoutes from "./routes/issueRoutes.js";
import adminRoutes from "./routes/adminRoutes.js";
import companyRegistrationRoutes from "./routes/companyRegistrationRoutes.js";
import notificationRoutes from "./routes/notificationRoutes.js";
import { sql } from "./config/db.js";
import { initRedisClient } from "./config/redisClient.js";
import { aj } from "./lib/arcjet.js";
import { runMigrations, createAdminAccount, createDefaultOwner, runSeeding } from "./lib/migrations.js";
import { requestProfiler } from "./middleware/requestProfiler.js";
import { initSocket } from "./realtime/socket.js";

dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;

// Important: express.json() must be before file upload routes
// But for file uploads, we need to handle multipart/form-data differently
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(cors());
app.use(helmet()); // helmet is a security middleware that helps protect your app from some well-known web vulnerabilities by setting HTTP headers appropriately.
app.use(morgan("dev")); // this logs the requests to the console
app.use(requestProfiler());

// Serve uploaded files
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
app.use('/uploads', express.static(join(__dirname, 'uploads')));
app.use('/uploads/company-documents', express.static(join(__dirname, 'uploads/company-documents')));

// apply arcjet rate limiting and bot detection middleware to all routes
// TEMPORARILY DISABLED FOR DEVELOPMENT
// app.use(async (req, res, next) => {
//     try {
//         const decision = await aj.protect(req, {
//             requested: 1, // each request costs 1 token
//         })
//         if (decision.isDenied()) {
//             if (decision.reason.isRateLimit()) {
//                 res.status(429).json({ error: "Too many requests - try again later" });
//             } else if (decision.reason.isBot()) {
//                 // Allow bots for development/testing
//                 console.log("Bot detected but allowing in development:", req.get('User-Agent'));
//                 next();
//                 return;
//             } else {
//                 res.status(403).json({ error: "Forbidden" });
//             }
//             return
//         }

//         next();
//     } catch (error) {
//         console.log("Arcjet error:", error);
//         next(error);
//     }
// });

app.use("/api/products", productRoutes);
app.use("/api/auth", authRoutes);
app.use("/api/users", userRoutes);
app.use("/api/debug", debugRoutes);

// Consumer routes
app.use("/api/consumer/auth", consumerAuthRoutes);
app.use("/api/consumer/catalog", consumerCatalogRoutes);
app.use("/api/company/consumers", companyConsumerRoutes);
app.use("/api/consumer/cart", consumerCartRoutes);

// Chat routes
app.use("/api/chat", chatRoutes);

// Order routes
app.use("/api/orders", orderRoutes);

// Issue reporting routes
app.use("/api/issues", issueRoutes);

// Notification routes
app.use("/api/notifications", notificationRoutes);

// Admin routes
app.use("/api/admin", adminRoutes);

// Company registration routes
app.use("/api/company", companyRegistrationRoutes);

async function initDB() {
    try {
        const shouldRunMigrations = process.env.RUN_MIGRATIONS !== "false";
        if (shouldRunMigrations) {
            try {
                await runMigrations();
            } catch (error) {
                if (error.code === '42710') {
                    console.warn("Migration skipped duplicate constraint:", error.constraint);
                } else {
                    throw error;
                }
            }
        } else {
            console.warn("RUN_MIGRATIONS=false - skipping database migrations at startup");
        }
        
        // Create legacy tables for backward compatibility (if needed)
        await sql`
            CREATE TABLE IF NOT EXISTS products (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                image VARCHAR(255) NOT NULL,
                price DECIMAL(15, 2) NOT NULL,
                discount_percentage DECIMAL(5, 2) DEFAULT 0,
                lead_time_days INT DEFAULT 0,
                minimum_order_quantity INT NOT NULL,
                available_quantity INT NOT NULL,
                company_id INTEGER REFERENCES companies(id) ON DELETE CASCADE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        
        // Update existing products table to increase price precision
        await sql`
            ALTER TABLE products 
            ALTER COLUMN price TYPE DECIMAL(15, 2)
        `;
        
        // Add discount_percentage column if it doesn't exist
        await sql`
            ALTER TABLE products 
            ADD COLUMN IF NOT EXISTS discount_percentage DECIMAL(5, 2) DEFAULT 0
        `;
        
        // Add lead_time_days column if it doesn't exist
        await sql`
            ALTER TABLE products 
            ADD COLUMN IF NOT EXISTS lead_time_days INT DEFAULT 0
        `;

        await sql`
            CREATE TABLE IF NOT EXISTS company_consumer_blocks (
                id SERIAL PRIMARY KEY,
                company_id INTEGER REFERENCES companies(id) ON DELETE CASCADE,
                consumer_id INTEGER REFERENCES consumer_users(id) ON DELETE CASCADE,
                blocked_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
                blocked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                unblocked_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
                unblocked_at TIMESTAMP NULL,
                is_blocked BOOLEAN DEFAULT TRUE,
                UNIQUE (company_id, consumer_id)
            )
        `;

        await sql`
            CREATE INDEX IF NOT EXISTS idx_company_consumer_blocks_active
            ON company_consumer_blocks (company_id, consumer_id)
            WHERE is_blocked = TRUE
        `;

        await sql`
            CREATE TABLE IF NOT EXISTS notifications (
                id SERIAL PRIMARY KEY,
                target_type VARCHAR(20) NOT NULL,
                target_consumer_id INTEGER,
                target_company_id INTEGER,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                action_type VARCHAR(50) NOT NULL,
                action_payload JSONB DEFAULT '{}'::jsonb,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                read_at TIMESTAMP NULL
            )
        `;

        await sql`
            CREATE INDEX IF NOT EXISTS idx_notifications_consumer_unread
            ON notifications (target_consumer_id)
            WHERE target_type = 'consumer' AND read_at IS NULL
        `;

        await sql`
            CREATE INDEX IF NOT EXISTS idx_notifications_company_unread
            ON notifications (target_company_id)
            WHERE target_type = 'company' AND read_at IS NULL
        `;
        
        // Create admin account
        await createAdminAccount();
        
        // Create default owner if no users exist
        await createDefaultOwner();
        
        // Run seeding for sample data
        await runSeeding();

        console.log("Database initialized successfully");
    } catch (error) {
        console.log("Error initDB", error);
    }
}

const server = http.createServer(app);
initSocket(server);

const startServer = async () => {
    try {
        await initDB();
        await initRedisClient();
    } catch (error) {
        console.error("Startup error:", error);
        process.exit(1);
    }

    server.listen(PORT, "0.0.0.0", () => {
        console.log(`Server is running on port ${PORT}`);
        console.log(`Accessible at: http://localhost:${PORT}`);
        console.log(`Network access: http://10.101.22.89:${PORT}`);
    });
};

startServer();

// app.listen(PORT, () => {
//     console.log("Server is running on port " + PORT);
// });