-- Admin-only document attachments per reservation (signed contracts, deposit
-- receipts, customer correspondence). Files live in the DB as BYTEA so:
--   * deletes cascade together with the reservation (GDPR right-to-erasure
--     wipes attachments without a separate object-store sweep), and
--   * uploads / reads share the same transaction boundary as the rest of
--     the reservation domain (no partial-write windows).
-- Volume is small (a handful of PDFs per booking), so the BYTEA cost is
-- acceptable in exchange for transactional safety.
CREATE TABLE reservation_document (
    id             BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservation(id) ON DELETE CASCADE,
    filename       VARCHAR(255) NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    size_bytes     BIGINT NOT NULL,
    data           BYTEA NOT NULL,
    -- uploaded_by is nullable + ON DELETE SET NULL so removing the admin
    -- account doesn't take their historical uploads down with them.
    uploaded_by    BIGINT REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_reservation_document_reservation_id
    ON reservation_document (reservation_id);
