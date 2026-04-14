ALTER TABLE users
    ADD COLUMN invite_status VARCHAR(31),
    ADD COLUMN invite_code VARCHAR(255),
    ADD COLUMN invite_time TIMESTAMP;

UPDATE users
SET invite_status = 'ACCEPTED'
WHERE invite_status IS NULL;

ALTER TABLE users
    ALTER COLUMN invite_status SET NOT NULL;
