-- Mollie's fee per payment in the admin "Payments" lists (Mario 11.11.2026).
--
-- provider_fee_minor = what Mollie deducted for the payment itself, in cents, read from the Balances API (balance
-- transaction `deductions`) by the hourly fee sync. That API needs an organisation access token (balances.read); the
-- payments API key cannot read it. NULL = not known (no token yet, or Mollie has not booked the payment yet): the
-- admin then shows a dash. Net = amount_minor - provider_fee_minor, computed when read.
--
-- Additive only (one nullable column on two small tables, no rewrite), idempotent. The old jar ignores the columns.
-- IF EXISTS only so the custom_payment integration test can run this file without the booking ledger; both tables
-- exist in every real database (V9_122, V9_123), and Hibernate validate refuses to start without the columns.
SET LOCAL lock_timeout = '5s';

ALTER TABLE IF EXISTS reservation_card_order
    ADD COLUMN IF NOT EXISTS provider_fee_minor BIGINT;

ALTER TABLE IF EXISTS custom_payment
    ADD COLUMN IF NOT EXISTS provider_fee_minor BIGINT;
