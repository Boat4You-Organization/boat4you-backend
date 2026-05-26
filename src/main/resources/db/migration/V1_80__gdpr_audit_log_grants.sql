-- V1_79 created `gdpr_audit_log` but forgot the GRANT to the application role.
-- All other tables in this codebase grant `SELECT, INSERT, UPDATE` (and where
-- applicable `USAGE, SELECT` on the sequence) to `boat4you_app`. Without it,
-- the running app — which connects as `boat4you_app`, not `boat4you_owner` —
-- gets `permission denied for sequence gdpr_audit_log_id_seq` on every
-- INSERT into the audit log, which in turn breaks the data export endpoint
-- (export tries to log itself before returning).
--
-- This migration patches the gap. Idempotent on Postgres — re-granting an
-- existing privilege is a no-op.
GRANT SELECT, INSERT, UPDATE ON gdpr_audit_log TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE gdpr_audit_log_id_seq TO boat4you_app;
