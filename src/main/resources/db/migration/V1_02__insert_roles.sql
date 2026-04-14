INSERT INTO roles (name, creator_id, modifier_id, created, modified, entity_status)
VALUES ('SYSTEM_ADMIN', 1, NULL, NOW(), NULL, 'ACTIVE'),
       ('MANAGER', 1, NULL, NOW(), NULL, 'ACTIVE'),
       ('USER', 1, NULL, NOW(), NULL, 'ACTIVE');