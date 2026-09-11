-- Baseline: the schema exactly as the Node backend produces it today.
--
-- In Node this DDL is spread across backend/lib/migrations.js (18 tables) and
-- backend/server.js initDB() (products, company_consumer_blocks, notifications), re-run
-- idempotently on every boot with no version table. It also has an ordering bug:
-- consumer_cart_items references products, which server.js only creates after
-- runMigrations() returns, and runMigrations swallows the resulting failure and reports
-- success -- so a fresh database needs two boots to end up complete. Here the order is
-- correct and a single pass produces the whole schema.
--
-- This file is deliberately faithful rather than improved, so that an existing database
-- can be honestly baselined at V1 with flyway.baseline-on-migrate. Corrections live in V3.

CREATE TABLE roles (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE permissions (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    resource VARCHAR(50) NOT NULL,
    action VARCHAR(50) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE role_permissions (
    id SERIAL PRIMARY KEY,
    role_id INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id INTEGER NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (role_id, permission_id)
);

CREATE TABLE users (
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
);

CREATE TABLE companies (
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
);

-- users.company_id carries no FK in the Node schema. migrations.js explicitly logs
-- "Skipping users company_id foreign key migration (already managed)". V3 adds it.
ALTER TABLE users ADD COLUMN company_id INTEGER;
ALTER TABLE users ADD COLUMN created_by INTEGER REFERENCES users(id);

CREATE TABLE products (
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
);

CREATE TABLE refresh_tokens (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_revoked BOOLEAN DEFAULT false
);

CREATE TABLE audit_log (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    resource VARCHAR(50) NOT NULL,
    resource_id INTEGER,
    details JSONB,
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE consumer_users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    is_active BOOLEAN DEFAULT true,
    last_login TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- status has no CHECK in the Node schema, but the controllers drive it through exactly six
-- values: pending, approved, rejected, revoked, blocked, cancelled.
CREATE TABLE company_consumer_requests (
    id SERIAL PRIMARY KEY,
    consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
    company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    status VARCHAR(20) DEFAULT 'pending',
    requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    responded_at TIMESTAMP,
    responded_by INTEGER REFERENCES users(id),
    UNIQUE (consumer_id, company_id)
);

-- expires_at is declared but never read or written anywhere in the Node codebase.
CREATE TABLE consumer_company_access (
    id SERIAL PRIMARY KEY,
    consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
    company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    granted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    granted_by INTEGER REFERENCES users(id),
    expires_at TIMESTAMP,
    is_active BOOLEAN DEFAULT true,
    revoked_at TIMESTAMP,
    UNIQUE (consumer_id, company_id)
);

CREATE TABLE consumer_refresh_tokens (
    id SERIAL PRIMARY KEY,
    consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_revoked BOOLEAN DEFAULT false
);

CREATE TABLE company_consumer_blocks (
    id SERIAL PRIMARY KEY,
    company_id INTEGER REFERENCES companies(id) ON DELETE CASCADE,
    consumer_id INTEGER REFERENCES consumer_users(id) ON DELETE CASCADE,
    blocked_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    blocked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    unblocked_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    unblocked_at TIMESTAMP,
    is_blocked BOOLEAN DEFAULT TRUE,
    UNIQUE (company_id, consumer_id)
);

CREATE TABLE consumer_carts (
    id SERIAL PRIMARY KEY,
    consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
    company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (consumer_id, company_id)
);

CREATE TABLE consumer_cart_items (
    id SERIAL PRIMARY KEY,
    cart_id INTEGER NOT NULL REFERENCES consumer_carts(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price DECIMAL(15, 2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (cart_id, product_id)
);

-- delivery_coordinates is the native Postgres POINT type, not PostGIS.
-- status has no CHECK; the code uses pending, accepted, rejected, completed.
CREATE TABLE consumer_orders (
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
);

-- product_name / unit_price / total_price are denormalised snapshots, so order history
-- survives the product being deleted (hence ON DELETE SET NULL and a nullable product_id).
CREATE TABLE consumer_order_items (
    id SERIAL PRIMARY KEY,
    order_id INTEGER NOT NULL REFERENCES consumer_orders(id) ON DELETE CASCADE,
    product_id INTEGER REFERENCES products(id) ON DELETE SET NULL,
    product_name VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price DECIMAL(15, 2) NOT NULL,
    total_price DECIMAL(15, 2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- sender_id is polymorphic: it points at consumer_users.id or users.id depending on
-- sender_type, so it cannot carry a foreign key.
CREATE TABLE consumer_company_messages (
    id SERIAL PRIMARY KEY,
    consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
    company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    sender_type VARCHAR(20) NOT NULL CHECK (sender_type IN ('consumer', 'company')),
    sender_id INTEGER NOT NULL,
    message_text TEXT NOT NULL,
    message_type VARCHAR(20) DEFAULT 'text',
    attachment_url TEXT,
    attachment_name TEXT,
    reply_to_message_id INTEGER REFERENCES consumer_company_messages(id) ON DELETE SET NULL,
    product_id INTEGER REFERENCES products(id) ON DELETE SET NULL,
    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- assigned_to_sales and closed are permitted by the CHECK but never written by any code path.
CREATE TABLE order_issues (
    id SERIAL PRIMARY KEY,
    order_id INTEGER NOT NULL REFERENCES consumer_orders(id) ON DELETE CASCADE,
    consumer_id INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
    company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    reported_by INTEGER NOT NULL REFERENCES consumer_users(id) ON DELETE CASCADE,
    assigned_to INTEGER REFERENCES users(id) ON DELETE SET NULL,
    assigned_to_role VARCHAR(50),
    status VARCHAR(50) DEFAULT 'reported'
        CHECK (status IN ('reported', 'assigned_to_sales', 'assigned_to_manager', 'resolved', 'closed')),
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    resolution_notes TEXT,
    resolved_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    assigned_at TIMESTAMP,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- file_data holds the PDF inline. Every SELECT * here drags the whole blob, so reads use
-- an explicit metadata projection.
CREATE TABLE company_documents (
    id SERIAL PRIMARY KEY,
    company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    originalname VARCHAR(255) NOT NULL,
    mimetype VARCHAR(100) NOT NULL,
    size INTEGER NOT NULL,
    file_data BYTEA NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Fully polymorphic and deliberately free of foreign keys: target_type selects which of
-- target_consumer_id / target_company_id is meaningful.
CREATE TABLE notifications (
    id SERIAL PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    target_consumer_id INTEGER,
    target_company_id INTEGER,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    action_payload JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role_id ON users(role_id);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
CREATE INDEX idx_role_permissions_role_id ON role_permissions(role_id);
CREATE INDEX idx_role_permissions_permission_id ON role_permissions(permission_id);
CREATE INDEX idx_consumer_users_email ON consumer_users(email);
CREATE INDEX idx_company_consumer_requests_consumer_id ON company_consumer_requests(consumer_id);
CREATE INDEX idx_company_consumer_requests_company_id ON company_consumer_requests(company_id);
CREATE INDEX idx_company_consumer_requests_status ON company_consumer_requests(status);
CREATE INDEX idx_consumer_company_access_consumer_id ON consumer_company_access(consumer_id);
CREATE INDEX idx_consumer_company_access_company_id ON consumer_company_access(company_id);
CREATE INDEX idx_consumer_refresh_tokens_consumer_id ON consumer_refresh_tokens(consumer_id);
CREATE INDEX idx_consumer_refresh_tokens_token_hash ON consumer_refresh_tokens(token_hash);
CREATE INDEX idx_consumer_carts_consumer_id ON consumer_carts(consumer_id);
CREATE INDEX idx_consumer_carts_company_id ON consumer_carts(company_id);
CREATE INDEX idx_consumer_cart_items_cart_id ON consumer_cart_items(cart_id);
CREATE INDEX idx_consumer_cart_items_product_id ON consumer_cart_items(product_id);
CREATE INDEX idx_consumer_orders_consumer_id ON consumer_orders(consumer_id);
CREATE INDEX idx_consumer_orders_company_id ON consumer_orders(company_id);
CREATE INDEX idx_consumer_orders_status ON consumer_orders(status);
CREATE INDEX idx_consumer_order_items_order_id ON consumer_order_items(order_id);
CREATE INDEX idx_consumer_order_items_product_id ON consumer_order_items(product_id);
CREATE INDEX idx_consumer_company_messages_consumer_id ON consumer_company_messages(consumer_id);
CREATE INDEX idx_consumer_company_messages_company_id ON consumer_company_messages(company_id);
CREATE INDEX idx_consumer_company_messages_sent_at ON consumer_company_messages(sent_at);
CREATE INDEX idx_consumer_company_messages_reply_to ON consumer_company_messages(reply_to_message_id);
CREATE INDEX idx_consumer_company_messages_product_id ON consumer_company_messages(product_id);
CREATE INDEX idx_order_issues_order_id ON order_issues(order_id);
CREATE INDEX idx_order_issues_consumer_id ON order_issues(consumer_id);
CREATE INDEX idx_order_issues_company_id ON order_issues(company_id);
CREATE INDEX idx_order_issues_assigned_to ON order_issues(assigned_to);
CREATE INDEX idx_order_issues_status ON order_issues(status);
CREATE INDEX idx_order_issues_reported_at ON order_issues(reported_at DESC);
CREATE INDEX idx_company_documents_company_id ON company_documents(company_id);

CREATE INDEX idx_company_consumer_blocks_active
    ON company_consumer_blocks (company_id, consumer_id)
    WHERE is_blocked = TRUE;

CREATE INDEX idx_notifications_consumer_unread
    ON notifications (target_consumer_id)
    WHERE target_type = 'consumer' AND read_at IS NULL;

CREATE INDEX idx_notifications_company_unread
    ON notifications (target_company_id)
    WHERE target_type = 'company' AND read_at IS NULL;
