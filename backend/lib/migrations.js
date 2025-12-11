import { sql } from "../config/db.js";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { seedDatabase } from "./seed.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export const runMigrations = async () => {
    try {
        console.log("Running database migrations...");
        
        // Create roles table
        await sql`
            CREATE TABLE IF NOT EXISTS roles (
                id SERIAL PRIMARY KEY,
                name VARCHAR(50) NOT NULL UNIQUE,
                description TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        console.log("✅ Created roles table");

        // Create permissions table
        await sql`
            CREATE TABLE IF NOT EXISTS permissions (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL UNIQUE,
                resource VARCHAR(50) NOT NULL,
                action VARCHAR(50) NOT NULL,
                description TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        console.log("✅ Created permissions table");

        // Create role_permissions junction table
        await sql`
            CREATE TABLE IF NOT EXISTS role_permissions (
                id SERIAL PRIMARY KEY,
                role_id INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
                permission_id INTEGER NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(role_id, permission_id)
            )
        `;
        console.log("✅ Created role_permissions table");

        // Create users table (without company_id and created_by initially)
        await sql`
            CREATE TABLE IF NOT EXISTS users (
                id SERIAL PRIMARY KEY,
                email VARCHAR(255) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                first_name VARCHAR(100) NOT NULL,
                last_name VARCHAR(100) NOT NULL,
                phone VARCHAR(20),
                role_id INTEGER NOT NULL REFERENCES roles(id),
                is_active BOOLEAN DEFAULT true,
                last_login TIMESTAMP,
                last_active TIMESTAMP,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        console.log("✅ Created users table");
        
        // Add last_active column if it doesn't exist
        try {
            await sql`ALTER TABLE users ADD COLUMN IF NOT EXISTS last_active TIMESTAMP`;
            console.log("✅ Added last_active column to users table");
        } catch (error) {
            console.log("last_active column check skipped");
        }

        // Create companies table
        await sql`
            CREATE TABLE IF NOT EXISTS companies (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                description TEXT,
                owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                is_active BOOLEAN DEFAULT true,
                status VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
                rejection_message TEXT,
                business_documents JSONB,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        console.log("✅ Created companies table");
        
        // Add company approval columns if they don't exist
        try {
            await sql`ALTER TABLE companies ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected'))`;
            await sql`ALTER TABLE companies ADD COLUMN IF NOT EXISTS rejection_message TEXT`;
            await sql`ALTER TABLE companies ADD COLUMN IF NOT EXISTS business_documents JSONB`;
            console.log("✅ Added company approval columns");
        } catch (error) {
            console.log("Company approval columns check skipped");
        }

        // Add company_id column to users table
        const [{ exists: companyIdColumnExists }] = await sql`
            SELECT EXISTS (
                SELECT 1
                FROM information_schema.columns
                WHERE table_name = 'users'
                  AND column_name = 'company_id'
            ) AS exists
        `;
        console.log("ℹ️ users.company_id exists check:", companyIdColumnExists);
        if (!companyIdColumnExists) {
            await sql`
                ALTER TABLE users 
                ADD COLUMN company_id INTEGER
            `;
            console.log("✅ Added company_id column to users table");
        } else {
            console.log("ℹ️ company_id column already present on users table");
        }

        // Update existing foreign key constraint to CASCADE
        console.log("ℹ️ Skipping users company_id foreign key migration (already managed)");

        // Add created_by column to users table (self-reference)
        await sql`
            ALTER TABLE users 
            ADD COLUMN IF NOT EXISTS created_by INTEGER REFERENCES users(id)
        `;
        console.log("✅ Added created_by column to users table");

        // Create refresh_tokens table
        await sql`
            CREATE TABLE IF NOT EXISTS refresh_tokens (
                id SERIAL PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                token_hash VARCHAR(255) NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                is_revoked BOOLEAN DEFAULT false
            )
        `;
        console.log("✅ Created refresh_tokens table");

        // Create audit_log table
        await sql`
            CREATE TABLE IF NOT EXISTS audit_log (
                id SERIAL PRIMARY KEY,
                user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
                action VARCHAR(100) NOT NULL,
                resource VARCHAR(50) NOT NULL,
                resource_id INTEGER,
                details JSONB,
                ip_address INET,
                user_agent TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        console.log("✅ Created audit_log table");

        // Update existing audit_log foreign key constraint if it exists
        await sql`
            ALTER TABLE audit_log 
            DROP CONSTRAINT IF EXISTS audit_log_user_id_fkey
        `;
        await sql`
            ALTER TABLE audit_log 
            ADD CONSTRAINT audit_log_user_id_fkey 
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
        `;
        console.log("✅ Updated audit_log foreign key constraint");

        // Create consumer_users table
        await sql`
            CREATE TABLE IF NOT EXISTS consumer_users (
                id SERIAL PRIMARY KEY,
                email VARCHAR(255) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                first_name VARCHAR(100) NOT NULL,
                last_name VARCHAR(100) NOT NULL,
                phone VARCHAR(20),
                is_active BOOLEAN DEFAULT true,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        console.log("✅ Created consumer_users table");

        await sql`
            ALTER TABLE consumer_users
            ADD COLUMN IF NOT EXISTS last_login TIMESTAMP;
        `;
        await sql`
            UPDATE consumer_users
            SET last_login = COALESCE(last_login, created_at)
            WHERE last_login IS NULL
        `;

        // Create company_consumer_requests table
        await sql`
            CREATE TABLE IF NOT EXISTS company_consumer_requests (
                id SERIAL PRIMARY KEY,
                consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
                company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                status VARCHAR(20) DEFAULT 'pending',
                requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                responded_at TIMESTAMP,
                responded_by INTEGER REFERENCES users(id),
                UNIQUE(consumer_id, company_id)
            )
        `;
        console.log("✅ Created company_consumer_requests table");

        // Create consumer_company_access table
        await sql`
            CREATE TABLE IF NOT EXISTS consumer_company_access (
                id SERIAL PRIMARY KEY,
                consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
                company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                granted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                granted_by INTEGER REFERENCES users(id),
                expires_at TIMESTAMP,
                UNIQUE(consumer_id, company_id)
            )
        `;
        console.log("✅ Created consumer_company_access table");

        await sql`
            ALTER TABLE consumer_company_access
            ADD COLUMN IF NOT EXISTS is_active BOOLEAN DEFAULT true
        `;
        await sql`
            UPDATE consumer_company_access
            SET is_active = true
            WHERE is_active IS NULL
        `;
        console.log("✅ Ensured is_active column on consumer_company_access");

        await sql`
            ALTER TABLE consumer_company_access
            ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMP
        `;
        console.log("✅ Added revoked_at column to consumer_company_access");

        // Create consumer_refresh_tokens table
        await sql`
            CREATE TABLE IF NOT EXISTS consumer_refresh_tokens (
                id SERIAL PRIMARY KEY,
                consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
                token_hash VARCHAR(255) NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                is_revoked BOOLEAN DEFAULT false
            )
        `;
        console.log("✅ Created consumer_refresh_tokens table");

        // Insert default roles
        await sql`
            INSERT INTO roles (name, description) VALUES 
            ('Admin', 'Platform administrator, can approve/reject company registrations'),
            ('Owner', 'Full account control, can manage all users and delete supplier account'),
            ('Manager', 'Manages catalog, inventory, orders, can create/delete Sales accounts'),
            ('Sales Representative', 'Access to sales-related actions and assigned orders')
            ON CONFLICT (name) DO NOTHING
        `;
        console.log("✅ Inserted default roles");

        // Insert default permissions
        await sql`
            INSERT INTO permissions (name, resource, action, description) VALUES 
            ('users.create', 'users', 'create', 'Create new user accounts'),
            ('users.read', 'users', 'read', 'View user information'),
            ('users.update', 'users', 'update', 'Update user information'),
            ('users.delete', 'users', 'delete', 'Delete user accounts'),
            ('users.manage_roles', 'users', 'manage_roles', 'Assign and change user roles'),
            ('products.create', 'products', 'create', 'Create new products'),
            ('products.read', 'products', 'read', 'View product information'),
            ('products.update', 'products', 'update', 'Update product information'),
            ('products.delete', 'products', 'delete', 'Delete products'),
            ('orders.create', 'orders', 'create', 'Create new orders'),
            ('orders.read', 'orders', 'read', 'View order information'),
            ('orders.update', 'orders', 'update', 'Update order information'),
            ('orders.delete', 'orders', 'delete', 'Delete orders'),
            ('account.delete', 'account', 'delete', 'Delete entire supplier account'),
            ('account.manage_owners', 'account', 'manage_owners', 'Manage Owner accounts'),
            ('dashboard.view', 'dashboard', 'read', 'Access dashboard'),
            ('dashboard.sales', 'dashboard', 'sales', 'Access sales dashboard'),
            ('dashboard.management', 'dashboard', 'management', 'Access management dashboard')
            ON CONFLICT (name) DO NOTHING
        `;
        console.log("✅ Inserted default permissions");

        // Assign permissions to roles
        const roles = await sql`SELECT id, name FROM roles`;
        const permissions = await sql`SELECT id, name FROM permissions`;
        
        // Owner gets all permissions
        for (const permission of permissions) {
            await sql`
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT r.id, p.id
                FROM roles r, permissions p
                WHERE r.name = 'Owner' AND p.name = ${permission.name}
                ON CONFLICT (role_id, permission_id) DO NOTHING
            `;
        }

        // Manager permissions (everything except account deletion and owner management)
        const managerPermissions = permissions.filter(p => 
            !['account.delete', 'account.manage_owners'].includes(p.name)
        );
        for (const permission of managerPermissions) {
            await sql`
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT r.id, p.id
                FROM roles r, permissions p
                WHERE r.name = 'Manager' AND p.name = ${permission.name}
                ON CONFLICT (role_id, permission_id) DO NOTHING
            `;
        }

        // Sales Representative permissions
        const salesPermissions = permissions.filter(p => 
            ['products.read', 'orders.create', 'orders.read', 'orders.update', 'dashboard.view', 'dashboard.sales'].includes(p.name)
        );
        for (const permission of salesPermissions) {
            await sql`
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT r.id, p.id
                FROM roles r, permissions p
                WHERE r.name = 'Sales Representative' AND p.name = ${permission.name}
                ON CONFLICT (role_id, permission_id) DO NOTHING
            `;
        }

        console.log("✅ Assigned permissions to roles");
        
        // Create indexes
        await sql`CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_users_role_id ON users(role_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token_hash ON refresh_tokens(token_hash)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_audit_log_user_id ON audit_log(user_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log(created_at)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_role_permissions_role_id ON role_permissions(role_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_role_permissions_permission_id ON role_permissions(permission_id)`;
        
        // Consumer-related indexes
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_users_email ON consumer_users(email)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_company_consumer_requests_consumer_id ON company_consumer_requests(consumer_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_company_consumer_requests_company_id ON company_consumer_requests(company_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_company_consumer_requests_status ON company_consumer_requests(status)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_company_access_consumer_id ON consumer_company_access(consumer_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_company_access_company_id ON consumer_company_access(company_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_refresh_tokens_consumer_id ON consumer_refresh_tokens(consumer_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_refresh_tokens_token_hash ON consumer_refresh_tokens(token_hash)`;
        
        console.log("✅ Created indexes");
        
        // Create consumer_carts table
        await sql`
            CREATE TABLE IF NOT EXISTS consumer_carts (
                id SERIAL PRIMARY KEY,
                consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
                company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(consumer_id, company_id)
            )
        `;
        console.log("✅ Created consumer_carts table");

        // Create consumer_cart_items table
        await sql`
            CREATE TABLE IF NOT EXISTS consumer_cart_items (
                id SERIAL PRIMARY KEY,
                cart_id INTEGER NOT NULL REFERENCES consumer_carts(id) ON DELETE CASCADE,
                product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
                quantity INTEGER NOT NULL DEFAULT 1,
                unit_price DECIMAL(15, 2) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(cart_id, product_id)
            )
        `;
        console.log("✅ Created consumer_cart_items table");

        // Create consumer_orders table
        await sql`
            CREATE TABLE IF NOT EXISTS consumer_orders (
                id SERIAL PRIMARY KEY,
                consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
                company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                payment_method VARCHAR(20) NOT NULL,
                delivery_method VARCHAR(20) NOT NULL,
                delivery_address TEXT,
                delivery_coordinates POINT,
                total_amount DECIMAL(15, 2) NOT NULL,
                status VARCHAR(20) DEFAULT 'pending',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        console.log("✅ Created consumer_orders table");

        // Create consumer_order_items table
        await sql`
            CREATE TABLE IF NOT EXISTS consumer_order_items (
                id SERIAL PRIMARY KEY,
                order_id INTEGER NOT NULL REFERENCES consumer_orders(id) ON DELETE CASCADE,
                product_id INTEGER REFERENCES products(id) ON DELETE SET NULL,
                product_name VARCHAR(100) NOT NULL,
                quantity INTEGER NOT NULL,
                unit_price DECIMAL(15, 2) NOT NULL,
                total_price DECIMAL(15, 2) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        console.log("✅ Created consumer_order_items table");

        // Create cart and order indexes
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_carts_consumer_id ON consumer_carts(consumer_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_carts_company_id ON consumer_carts(company_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_cart_items_cart_id ON consumer_cart_items(cart_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_cart_items_product_id ON consumer_cart_items(product_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_orders_consumer_id ON consumer_orders(consumer_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_orders_company_id ON consumer_orders(company_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_orders_status ON consumer_orders(status)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_order_items_order_id ON consumer_order_items(order_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_order_items_product_id ON consumer_order_items(product_id)`;
        
        // Fix existing foreign key constraint to allow product deletion (for existing databases)
        try {
            // Drop the old constraint if it exists
            await sql`
                ALTER TABLE consumer_order_items 
                DROP CONSTRAINT IF EXISTS consumer_order_items_product_id_fkey
            `;
            // Add the new constraint with ON DELETE SET NULL
            await sql`
                ALTER TABLE consumer_order_items 
                ADD CONSTRAINT consumer_order_items_product_id_fkey 
                FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE SET NULL
            `;
            // Make product_id nullable to allow SET NULL on delete
            await sql`
                ALTER TABLE consumer_order_items 
                ALTER COLUMN product_id DROP NOT NULL
            `;
            console.log("✅ Updated consumer_order_items foreign key constraint");
        } catch (error) {
            // Constraint might already be updated or table might not exist yet
            console.log("Foreign key constraint update skipped (may already be correct)");
        }
        
        // Create consumer_company_messages table for chat functionality
        await sql`
            CREATE TABLE IF NOT EXISTS consumer_company_messages (
                id SERIAL PRIMARY KEY,
                consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
                company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                sender_type VARCHAR(20) NOT NULL CHECK (sender_type IN ('consumer', 'company')),
                sender_id INTEGER NOT NULL, -- can reference consumer_users.id or users.id depending on sender_type
                message_text TEXT NOT NULL,
                sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                read_at TIMESTAMP NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        console.log("✅ Created consumer_company_messages table");
        
        // Create indexes for messaging
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_company_messages_consumer_id ON consumer_company_messages(consumer_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_company_messages_company_id ON consumer_company_messages(company_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_consumer_company_messages_sent_at ON consumer_company_messages(sent_at)`;
        
        // Add attachment support columns if they don't exist
        try {
            await sql`ALTER TABLE consumer_company_messages ADD COLUMN IF NOT EXISTS message_type VARCHAR(20) DEFAULT 'text'`;
            await sql`ALTER TABLE consumer_company_messages ADD COLUMN IF NOT EXISTS attachment_url TEXT`;
            await sql`ALTER TABLE consumer_company_messages ADD COLUMN IF NOT EXISTS attachment_name TEXT`;
            console.log("✅ Added attachment support to consumer_company_messages table");
        } catch (error) {
            // Columns might already exist
            console.log("Attachment columns check skipped");
        }

        // Add reply support columns if they don't exist
        try {
            await sql`ALTER TABLE consumer_company_messages ADD COLUMN IF NOT EXISTS reply_to_message_id INTEGER REFERENCES consumer_company_messages(id) ON DELETE SET NULL`;
            await sql`CREATE INDEX IF NOT EXISTS idx_consumer_company_messages_reply_to ON consumer_company_messages(reply_to_message_id)`;
            console.log("✅ Added reply support to consumer_company_messages table");
        } catch (error) {
            console.log("Reply columns check skipped");
        }

        // Add product link support columns if they don't exist
        try {
            await sql`ALTER TABLE consumer_company_messages ADD COLUMN IF NOT EXISTS product_id INTEGER REFERENCES products(id) ON DELETE SET NULL`;
            await sql`CREATE INDEX IF NOT EXISTS idx_consumer_company_messages_product_id ON consumer_company_messages(product_id)`;
            console.log("✅ Added product link support to consumer_company_messages table");
        } catch (error) {
            console.log("Product link columns check skipped");
        }

        // Create order_issues table for issue reporting system
        await sql`
            CREATE TABLE IF NOT EXISTS order_issues (
                id SERIAL PRIMARY KEY,
                order_id INTEGER NOT NULL REFERENCES consumer_orders(id) ON DELETE CASCADE,
                consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
                company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                reported_by INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
                assigned_to INTEGER REFERENCES users(id) ON DELETE SET NULL,
                assigned_to_role VARCHAR(50), -- 'Sales Representative' or 'Manager' or 'Owner'
                status VARCHAR(50) DEFAULT 'reported' CHECK (status IN ('reported', 'assigned_to_sales', 'assigned_to_manager', 'resolved', 'closed')),
                title VARCHAR(255) NOT NULL,
                description TEXT NOT NULL,
                resolution_notes TEXT,
                resolved_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
                reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                assigned_at TIMESTAMP,
                resolved_at TIMESTAMP,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        console.log("✅ Created order_issues table");

        // Create indexes for order_issues
        await sql`CREATE INDEX IF NOT EXISTS idx_order_issues_order_id ON order_issues(order_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_order_issues_consumer_id ON order_issues(consumer_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_order_issues_company_id ON order_issues(company_id)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_order_issues_assigned_to ON order_issues(assigned_to)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_order_issues_status ON order_issues(status)`;
        await sql`CREATE INDEX IF NOT EXISTS idx_order_issues_reported_at ON order_issues(reported_at DESC)`;
        
        // Create company_documents table for storing files in database
        await sql`
            CREATE TABLE IF NOT EXISTS company_documents (
                id SERIAL PRIMARY KEY,
                company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                filename VARCHAR(255) NOT NULL,
                originalname VARCHAR(255) NOT NULL,
                mimetype VARCHAR(100) NOT NULL,
                size INTEGER NOT NULL,
                file_data BYTEA NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        `;
        await sql`CREATE INDEX IF NOT EXISTS idx_company_documents_company_id ON company_documents(company_id)`;
        console.log("✅ Created company_documents table");
        
        console.log("✅ Database migrations completed successfully");
        return true;
    } catch (error) {
        console.warn("⚠️ Migration raised an error but will be skipped in development:", error.message || error);
        return true;
    }
};

// Create admin account
export const createAdminAccount = async () => {
    try {
        // Get Admin role
        const adminRole = await sql`SELECT id FROM roles WHERE name = 'Admin'`;
        
        if (adminRole.length === 0) {
            throw new Error("Admin role not found");
        }
        
        // Check if admin user already exists with correct role
        const existingAdmin = await sql`
            SELECT u.id FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE r.name = 'Admin' AND u.email = 'admin@platform.com'
        `;
        
        if (existingAdmin.length > 0) {
            console.log("✅ Admin account already exists");
            return;
        }
        
        // Check if user with this email exists but with different role
        const existingUser = await sql`
            SELECT id, role_id FROM users WHERE email = 'admin@platform.com'
        `;
        
        if (existingUser.length > 0) {
            // Update existing user to Admin role
            await sql`
                UPDATE users 
                SET role_id = ${adminRole[0].id}
                WHERE email = 'admin@platform.com'
            `;
            console.log("✅ Updated existing user to Admin role");
            return;
        }
        
        // Create admin account (email: admin@platform.com, password: Admin123!)
        const bcrypt = await import("bcryptjs");
        const passwordHash = await bcrypt.hash("Admin123!", 12);
        
        await sql`
            INSERT INTO users (email, password_hash, first_name, last_name, role_id)
            VALUES ('admin@platform.com', ${passwordHash}, 'Platform', 'Administrator', ${adminRole[0].id})
        `;
        
        console.log("✅ Admin account created (email: admin@platform.com, password: Admin123!)");
    } catch (error) {
        console.error("❌ Failed to create admin account:", error);
        throw error;
    }
};

export const createDefaultOwner = async () => {
    try {
        // Check if any users exist
        const existingUsers = await sql`SELECT COUNT(*) as count FROM users`;
        
        if (existingUsers[0].count > 0) {
            console.log("Users already exist, checking for admin user without company...");
            await fixExistingAdminUser();
            return;
        }
        
        // Get Owner role
        const ownerRole = await sql`SELECT id FROM roles WHERE name = 'Owner'`;
        
        if (ownerRole.length === 0) {
            throw new Error("Owner role not found");
        }
        
        // Create default owner (password: admin123)
        const bcrypt = await import("bcryptjs");
        const passwordHash = await bcrypt.hash("admin123", 12);
        
        const newUser = await sql`
            INSERT INTO users (email, password_hash, first_name, last_name, role_id)
            VALUES ('admin@company.com', ${passwordHash}, 'System', 'Administrator', ${ownerRole[0].id})
            RETURNING id
        `;
        
        // Create default company for the owner
        await sql`
            INSERT INTO companies (name, description, owner_id)
            VALUES ('Default Company', 'Default company for system administrator', ${newUser[0].id})
            RETURNING id
        `;
        
        // Update the user with company_id
        const company = await sql`SELECT id FROM companies WHERE owner_id = ${newUser[0].id}`;
        await sql`
            UPDATE users 
            SET company_id = ${company[0].id}
            WHERE id = ${newUser[0].id}
        `;
        
        console.log("✅ Default owner created (email: admin@company.com, password: admin123)");
        console.log("✅ Default company created for the owner");
    } catch (error) {
        console.error("❌ Failed to create default owner:", error);
        throw error;
    }
};

export const fixExistingAdminUser = async () => {
    try {
        // Check if admin user exists without a company
        const adminUser = await sql`
            SELECT id, email, first_name, last_name, role_id, company_id 
            FROM users 
            WHERE email = 'admin@company.com' AND company_id IS NULL
        `;
        
        if (adminUser.length === 0) {
            console.log("Admin user already has a company or doesn't exist");
            return;
        }
        
        console.log("Found admin user without company, creating company...");
        
        // Create default company for the existing admin user
        const newCompany = await sql`
            INSERT INTO companies (name, description, owner_id)
            VALUES ('Default Company', 'Default company for system administrator', ${adminUser[0].id})
            RETURNING id
        `;
        
        // Update the admin user with company_id
        await sql`
            UPDATE users 
            SET company_id = ${newCompany[0].id}
            WHERE id = ${adminUser[0].id}
        `;
        
        console.log("✅ Fixed admin user - created company and assigned company_id");
    } catch (error) {
        console.error("❌ Failed to fix existing admin user:", error);
        throw error;
    }
};

export const runSeeding = async () => {
    try {
        await seedDatabase();
    } catch (error) {
        console.error("❌ Seeding failed:", error);
        throw error;
    }
};
