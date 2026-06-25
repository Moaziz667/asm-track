-- Seed admin users (name mirror; Keycloak realm import is the matching name master).
INSERT INTO admin_users (id, name, email, role, active) VALUES
('11111111-1111-1111-1111-111111111111', 'Aziz Hadjkacem', 'admin@asm.com', 'ADMIN', true)
ON CONFLICT (email) DO NOTHING;

INSERT INTO admin_users (id, name, email, role, active) VALUES
('22222222-2222-2222-2222-222222222222', 'Sofiene Brahmi', 'dispatcher@asm.com', 'DISPATCHER', true)
ON CONFLICT (email) DO NOTHING;

INSERT INTO admin_users (id, name, email, role, active) VALUES
('66666666-6666-6666-6666-666666666666', 'Nadia Cherif', 'manager@asm.com', 'MANAGER', true)
ON CONFLICT (email) DO NOTHING;
