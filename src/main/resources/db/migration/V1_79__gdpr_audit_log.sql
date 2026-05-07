-- GDPR audit log — what was requested, by whom, when, completed when.
--
-- Required by GDPR Article 5(2) "accountability principle" — controller must
-- be able to demonstrate compliance, which in practice means a log of every
-- right-exercise event (delete, export, rectification, etc.). Soft constraint
-- (no legal mandate to keep this for X years), but auditors will ask for it.
--
-- One row per request, written from VoucherService / UserMutationService /
-- DataExportService at the moment the operation succeeds. We log even the
-- request IP because Article 5 wants to demonstrate that the request really
-- came from the data subject (not an attacker who got hold of the JWT).
--
-- Retention: keep these rows alongside the user record itself. After a user
-- is fully purged from the system (right now: never, we soft-delete), the
-- audit log can be purged too — until then it stays.
CREATE SEQUENCE gdpr_audit_log_id_seq;

CREATE TABLE gdpr_audit_log (
    id              BIGINT       PRIMARY KEY DEFAULT nextval('gdpr_audit_log_id_seq'),
    user_id         BIGINT       NOT NULL,
    action          VARCHAR(32)  NOT NULL,                      -- DELETE_ACCOUNT / EXPORT_DATA / RECTIFY / OBJECT
    requested_at    TIMESTAMP    NOT NULL DEFAULT now(),
    completed_at    TIMESTAMP,                                  -- null until success; never set on failure
    request_ip      VARCHAR(45),                                -- IPv6-safe length
    request_user_agent VARCHAR(500),
    notes           TEXT,                                       -- optional context (e.g. "anonymized 1 user, kept N reservations")
    CONSTRAINT gdpr_audit_user_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX gdpr_audit_log_user_idx ON gdpr_audit_log (user_id, requested_at DESC);
CREATE INDEX gdpr_audit_log_action_idx ON gdpr_audit_log (action, requested_at DESC);
