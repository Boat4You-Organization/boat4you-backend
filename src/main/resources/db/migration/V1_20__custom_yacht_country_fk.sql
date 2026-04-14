ALTER TABLE custom_yacht_details
    RENAME COLUMN country_id TO country_key;
ALTER TABLE custom_yacht_details
    ADD COLUMN country_id BIGINT;

ALTER TABLE custom_yacht_details
    ADD CONSTRAINT fk_custom_yacht_details_country
        FOREIGN KEY (country_id) REFERENCES country (id);

CREATE INDEX idx_custom_yacht_details_country_id ON custom_yacht_details (country_id);