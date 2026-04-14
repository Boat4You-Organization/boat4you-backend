ALTER TABLE users
    ADD COLUMN email_verification_code     VARCHAR(255),
    ADD COLUMN registration_status         VARCHAR(31),
    ADD COLUMN verification_code_issued_at TIMESTAMP;

UPDATE users
SET registration_status = 'REGISTERED'
WHERE users.registration_status IS NULL;

ALTER TABLE users
    ALTER COLUMN registration_status SET NOT NULL;
