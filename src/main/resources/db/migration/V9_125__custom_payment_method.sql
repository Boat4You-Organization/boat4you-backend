-- Klarna on the secret /pay link (Mario 10.11.2026), twin of V9_124.
--
-- custom_payment.method = 'CARD' (cards, Apple Pay, Pay by Bank — as before) or 'KLARNA'. The "same data again returns
-- the open checkout" dedupe must not hand a card checkout to a payer who now picked Klarna. NULL = a row from before
-- this migration = CARD.
--
-- Additive only (one nullable column on a small table, no rewrite), idempotent. The old jar ignores the column.
SET LOCAL lock_timeout = '5s';

ALTER TABLE custom_payment
    ADD COLUMN IF NOT EXISTS method VARCHAR(16);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_custom_payment_method') THEN
        ALTER TABLE custom_payment
            ADD CONSTRAINT chk_custom_payment_method CHECK (method IS NULL OR method IN ('CARD', 'KLARNA'));
    END IF;
END $$;
