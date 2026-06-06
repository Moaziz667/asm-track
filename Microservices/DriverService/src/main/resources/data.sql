INSERT INTO drivers (id, name, phone, email, account_status, online_status, is_registered, created_at, updated_at) VALUES
('33333333-3333-3333-3333-333333333333', 'Ahmed Driver', '+21620000001', 'driver1@asm.com', 'VERIFIED', 'OFFLINE', true, NOW(), NOW())
ON CONFLICT (phone) DO NOTHING;

INSERT INTO drivers (id, name, phone, email, account_status, online_status, is_registered, created_at, updated_at) VALUES
('44444444-4444-4444-4444-444444444444', 'Sami Driver', '+21620000002', 'driver2@asm.com', 'VERIFIED', 'OFFLINE', true, NOW(), NOW())
ON CONFLICT (phone) DO NOTHING;

INSERT INTO drivers (id, name, phone, email, account_status, online_status, is_registered, created_at, updated_at) VALUES
('55555555-5555-5555-5555-555555555555', 'Karim Driver', '+21620000003', 'driver3@asm.com', 'VERIFIED', 'OFFLINE', true, NOW(), NOW())
ON CONFLICT (phone) DO NOTHING;
