-- Payment-method fees (Mario 1.7.2026):
--   * card payments carry a 5% processing surcharge on the amount being paid
--     (per installment) — applied to the Stripe charge by StripePaymentService;
--   * bank transfers carry a fixed 32 EUR fee per reservation, split evenly
--     across payment installments (2 phases -> 16 EUR per wire) — applied to
--     the wire "Transfer amount" in the customer emails and the payment UI.
-- Values are admin-editable via AdminSettingsController; this migration seeds
-- the current policy (upsert, so re-runs / later admin edits behave sanely).
INSERT INTO settings (name, value, created, entity_status)
VALUES ('CARD_PAYMENT_SURCHARGE', '5', NOW(), 'ACTIVE'),
       ('BANK_TRANSFER_FIXED_FEE', '32', NOW(), 'ACTIVE')
ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, modified = NOW();
