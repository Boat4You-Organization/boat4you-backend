-- Klarna next to cards on the Mollie booking checkout (Mario 10.11.2026).
--
-- A booking checkout is created for ONE payment method group: 'CARD' (cards + Apple Pay, as before) or 'KLARNA' (Klarna
-- only: Mollie needs the billing address and order lines for it). The method is part of "reuse before create": a payer
-- who opened the card checkout and then picks Klarna for the same instalment must get a NEW Mollie payment, not the
-- card checkout handed out again. NULL = a row from before this migration = CARD.
--
-- Additive only (one nullable column on a small table, no rewrite), idempotent. The old jar ignores the column.
-- custom_payment gets the same column in V9_125.
SET LOCAL lock_timeout = '5s';

ALTER TABLE reservation_card_order
    ADD COLUMN IF NOT EXISTS method VARCHAR(16);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_reservation_card_order_method') THEN
        ALTER TABLE reservation_card_order
            ADD CONSTRAINT chk_reservation_card_order_method CHECK (method IS NULL OR method IN ('CARD', 'KLARNA'));
    END IF;
END $$;
