-- Seed the initial admin user for production.
-- Password MUST be changed after first login.
INSERT INTO users (id, name, surname, password, email, creator_id, modifier_id, created, modified, entity_status, login_attempts, language, currency, registration_status, invite_status)
VALUES (1, 'Mario', 'Kuzmanic', '$2b$10$j5oIzHrFeAvRcZ3YqUHugud3hLZgiRIN9r.RgcPr7KqNcq2X2/S5S', 'mkuzmani@gmail.com', 1, NULL, NOW(), NULL, 'ACTIVE', 0, 'EN', 'EUR', 'REGISTERED', 'ACCEPTED');

INSERT INTO role_assignments (id, user_id, role_id, creator_id, modifier_id, created, modified, entity_status)
VALUES (1, 1, 1, 1, NULL, NOW(), NULL, 'ACTIVE');

SELECT pg_catalog.setval('public.users_id_seq', 1, true);
SELECT pg_catalog.setval('public.role_assignments_id_seq', 1, true);
