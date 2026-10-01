-- Add role column to users table with CHECK constraint
ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'ROLE_CUSTOMER'
    CHECK (role IN ('ROLE_CUSTOMER', 'ROLE_MAKER', 'ROLE_CHECKER', 'ROLE_REVERSAL_APPROVER', 'ROLE_ADMIN', 'ROLE_AUDITOR'));

-- Update system user role to ROLE_ADMIN
UPDATE users SET role = 'ROLE_ADMIN' WHERE id = '00000000-0000-0000-0000-000000000001';

-- Seed default role accounts for tests & demo
-- Password for all seeded demo users is: password123 (BCrypt hash)
INSERT INTO users (id, email, password_hash, role, status)
VALUES 
    ('10000000-0000-0000-0000-000000000001', 'admin@platform.internal', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ROLE_ADMIN', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000002', 'maker@platform.internal', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ROLE_MAKER', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000003', 'checker@platform.internal', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ROLE_CHECKER', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000004', 'approver@platform.internal', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ROLE_REVERSAL_APPROVER', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000005', 'auditor@platform.internal', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ROLE_AUDITOR', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000006', 'customer@platform.internal', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ROLE_CUSTOMER', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;
