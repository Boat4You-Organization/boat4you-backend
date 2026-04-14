DROP VIEW IF EXISTS custom_yacht_view;
ALTER TABLE boat4you_db.public.custom_yacht_details DROP COLUMN IF EXISTS mid_price;
ALTER TABLE boat4you_db.public.custom_yacht_details DROP COLUMN IF EXISTS high_price;
ALTER TABLE boat4you_db.public.custom_yacht_details DROP COLUMN IF EXISTS mid_price_description;
ALTER TABLE boat4you_db.public.custom_yacht_details DROP COLUMN IF EXISTS high_price_description;

ALTER TABLE boat4you_db.public.custom_yacht_details RENAME low_price_description to price_description;
ALTER TABLE boat4you_db.public.custom_yacht_details ALTER COLUMN price_description TYPE varchar(2500);
