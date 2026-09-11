-- The schema half of the approved defect fixes. Kept separate from V1 so that an existing
-- database baselined at V1 still receives them.

-- Fix 7. orderController.js:356 writes products.updated_at when restoring stock on an order
-- rejection, but the column has never existed -- so every rejection of a previously accepted
-- order throws. Backfilled from created_at so existing rows are not null.
ALTER TABLE products ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
UPDATE products SET updated_at = created_at WHERE updated_at IS NULL;

-- Fix 8. company_id is the filter on every products read, and had no index.
CREATE INDEX IF NOT EXISTS idx_products_company_id ON products(company_id);

-- Fix 9. users.company_id was left unconstrained. SET NULL rather than CASCADE: deleting a
-- company must not silently delete its staff, and the login path already handles a user
-- whose company_id is null.
UPDATE users u SET company_id = NULL
WHERE company_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM companies c WHERE c.id = u.company_id);

ALTER TABLE users
    ADD CONSTRAINT users_company_id_fkey
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_users_company_id ON users(company_id);

-- Fix 6, schema half. The Node decrement is GREATEST(0, available_quantity - qty), which
-- masks the stock race rather than preventing it. With the order row and product rows locked
-- the clamp is unnecessary, so the decrement becomes a plain subtraction and this constraint
-- turns any residual underflow into a loud failure instead of silent data corruption.
UPDATE products SET available_quantity = 0 WHERE available_quantity < 0;

ALTER TABLE products
    ADD CONSTRAINT products_available_quantity_nonneg CHECK (available_quantity >= 0);

-- Refresh tokens are looked up by hash as if it were a key, but neither table had a unique
-- index on it. Partial, because revoked rows are retained and may legitimately collide.
CREATE UNIQUE INDEX IF NOT EXISTS idx_refresh_tokens_hash_unique
    ON refresh_tokens(token_hash) WHERE is_revoked = false;

CREATE UNIQUE INDEX IF NOT EXISTS idx_consumer_refresh_tokens_hash_unique
    ON consumer_refresh_tokens(token_hash) WHERE is_revoked = false;
