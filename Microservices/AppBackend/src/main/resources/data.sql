INSERT INTO admin_users (id, name, email, role, active) VALUES
('11111111-1111-1111-1111-111111111111', 'System Admin', 'admin@asm.com', 'ADMIN', true)
ON CONFLICT (email) DO NOTHING;

INSERT INTO admin_users (id, name, email, role, active) VALUES
('22222222-2222-2222-2222-222222222222', 'Chief Dispatcher', 'dispatcher@asm.com', 'DISPATCHER', true)
ON CONFLICT (email) DO NOTHING;
