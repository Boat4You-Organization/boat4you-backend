-- V1_55: Remove the entire Viva Wallet payment integration.
--
-- Mario's call (23.4.2026): drop Viva entirely. Backend services, controllers,
-- DTOs and config keys are gone. This migration removes the two columns
-- `viva_order_code` and `viva_transaction_id` from `reservation_payment_phase`
-- so the JPA entity (which no longer references them) matches the schema.
--
-- These columns were added by V1_37 in a brief Viva trial and never carried
-- production-relevant data; safe to drop without backfill.

ALTER TABLE reservation_payment_phase
    DROP COLUMN IF EXISTS viva_order_code,
    DROP COLUMN IF EXISTS viva_transaction_id;
