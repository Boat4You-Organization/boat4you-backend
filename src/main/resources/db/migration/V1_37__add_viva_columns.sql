ALTER TABLE reservation_payment_phase
    ADD COLUMN IF NOT EXISTS viva_order_code     VARCHAR(511),
    ADD COLUMN IF NOT EXISTS viva_transaction_id VARCHAR(511);
