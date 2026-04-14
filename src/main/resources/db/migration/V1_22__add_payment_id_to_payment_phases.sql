ALTER TABLE reservation_payment_phase
    ADD COLUMN IF NOT EXISTS  stripe_payment_intent_id VARCHAR(511);
