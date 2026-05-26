-- V1_83 created `reservation_document` (admin-only file attachments per
-- reservation) but ran as `boat4you_owner` and didn't grant access to the
-- runtime app role. Without these GRANTs, every upload fails with
-- `permission denied for sequence reservation_document_id_seq` on the
-- INSERT — same gap V1_80 patched for `gdpr_audit_log`.
--
-- DELETE is granted here too because the admin UI exposes a "Delete"
-- action per attached document, separate from the cascade-on-reservation
-- delete that Postgres handles itself via the FK.
--
-- Idempotent on Postgres — re-granting an existing privilege is a no-op.
GRANT SELECT, INSERT, UPDATE, DELETE ON reservation_document TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE reservation_document_id_seq TO boat4you_app;
