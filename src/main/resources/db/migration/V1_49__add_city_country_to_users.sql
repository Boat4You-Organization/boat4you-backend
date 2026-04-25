-- Follow-up to V1_48: split postal address into street (already added as
-- `address`), city, and country. Agencies like Nausys/MMK need city+country
-- as separate fields on the crew list / reservation.
ALTER TABLE users ADD COLUMN city VARCHAR(100);
ALTER TABLE users ADD COLUMN country VARCHAR(100);
