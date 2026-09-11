-- Reference data the application cannot run without: users.role_id is NOT NULL, and role
-- names are string-matched throughout the code (adminAuth checks roleName === 'Admin',
-- issue assignment checks for 'Manager' or 'Owner', and so on).
--
-- ON CONFLICT DO NOTHING mirrors the Node seed, so re-running against a database that
-- already has these rows is a no-op and existing ids are left alone.

INSERT INTO roles (name, description) VALUES
    ('Admin',                'Platform administrator with full system access'),
    ('Owner',                'Company owner with full access to company resources'),
    ('Manager',              'Manager with access to most company resources'),
    ('Sales Representative', 'Sales representative with limited access')
ON CONFLICT (name) DO NOTHING;

INSERT INTO permissions (name, resource, action, description) VALUES
    ('users.create',         'users',     'create',      'Create new users'),
    ('users.read',           'users',     'read',        'View users'),
    ('users.update',         'users',     'update',      'Update users'),
    ('users.delete',         'users',     'delete',      'Delete users'),
    ('users.manage_roles',   'users',     'manage_roles','Assign roles to users'),
    ('products.create',      'products',  'create',      'Create products'),
    ('products.read',        'products',  'read',        'View products'),
    ('products.update',      'products',  'update',      'Update products'),
    ('products.delete',      'products',  'delete',      'Delete products'),
    ('orders.create',        'orders',    'create',      'Create orders'),
    ('orders.read',          'orders',    'read',        'View orders'),
    ('orders.update',        'orders',    'update',      'Update orders'),
    ('orders.delete',        'orders',    'delete',      'Delete orders'),
    ('account.delete',       'account',   'delete',      'Delete the company account'),
    ('account.manage_owners','account',   'manage_owners','Manage company owners'),
    ('dashboard.view',       'dashboard', 'view',        'View the dashboard'),
    ('dashboard.sales',      'dashboard', 'sales',       'View sales dashboard'),
    ('dashboard.management', 'dashboard', 'management',  'View management dashboard')
ON CONFLICT (name) DO NOTHING;

-- Owner: every permission.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'Owner'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Manager: everything except the two account-level permissions.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'Manager'
  AND p.name NOT IN ('account.delete', 'account.manage_owners')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Sales Representative: a fixed six.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'Sales Representative'
  AND p.name IN ('products.read', 'orders.create', 'orders.read', 'orders.update',
                 'dashboard.view', 'dashboard.sales')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Admin intentionally gets no role_permissions rows. Admin authority comes entirely from
-- the roleName === 'Admin' check in the adminAuth middleware, not from the permission table.
